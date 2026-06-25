package com.nsn8.vued.capture

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Discovers, claims, and streams raw PCM from a miniDSP UMA-8 or UMA-16 mic array.
 *
 * Both arrays stream 24-bit samples in 32-bit LE subslots at [SAMPLE_RATE_HZ] over
 * UAC2 isochronous IN; they differ only in how many channel slots ride each device
 * frame and how many of those slots carry real mics:
 *   UMA-8    : 8 slots/frame, 7 real mics + 1 always-zero slot (emit 7).
 *   UMA-16 v2: 16 slots/frame, 16 real MEMS mics in a 4x4 URA (emit all 16).
 *
 * The native layer emits interleaved `profile.outChannels` int32 little-endian
 * samples per frame. [streamPcm] blocks on the calling thread, invoking
 * `onPcm(buffer, length)` with a reused buffer — the consumer must process each
 * callback synchronously before returning (no cross-thread retention).
 *
 * If [profileOverride] is null, the profile is selected from the connected
 * device's vendor id / product name; pass an explicit profile to force one.
 */
class Uma8Capture @JvmOverloads constructor(
    context: Context,
    private val profileOverride: MicArrayProfile? = null,
) {

    private val usbManager: UsbManager =
        context.getSystemService(UsbManager::class.java)

    class CaptureException(message: String) : Exception(message)

    /** Profile that matched at the most recent device lookup / stream start. */
    @Volatile
    var activeProfile: MicArrayProfile = profileOverride ?: PROFILE_UMA8
        private set

    /** Returns the connected mic array (or null), without requiring permission. */
    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull(::isMicArrayDevice)

    /**
     * Resolves the active profile for the connected device. Override (if any) wins;
     * otherwise infers from vendor id / product name. Returns null if no array is
     * connected — callers can fall back to a configured default.
     */
    fun resolveProfile(): MicArrayProfile? {
        val device = findDevice() ?: return null
        return (profileOverride ?: profileFor(device)).also { activeProfile = it }
    }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /**
     * Opens the device and pumps PCM into [onPcm] until [shouldContinue] returns false
     * or a fatal error occurs. Throws [CaptureException] on any setup failure.
     */
    fun streamPcm(
        onPcm: (buffer: ByteArray, length: Int) -> Unit,
        shouldContinue: () -> Boolean,
    ) {
        val device = findDevice()
            ?: throw CaptureException("mic array not connected")
        if (!usbManager.hasPermission(device)) {
            throw CaptureException("USB permission not granted for mic array")
        }
        val profile = (profileOverride ?: profileFor(device)).also { activeProfile = it }
        val captureInterface = findCaptureInterface(device)
            ?: throw CaptureException("No ${profile.label} IN isochronous interface found")
        val endpoint = findIsoInEndpoint(captureInterface)
            ?: throw CaptureException("Capture interface has no IN isochronous endpoint")

        val connection = usbManager.openDevice(device)
            ?: throw CaptureException("openDevice failed")

        try {
            if (!connection.claimInterface(captureInterface, true)) {
                throw CaptureException("claimInterface failed")
            }
            if (!connection.setInterface(captureInterface)) {
                throw CaptureException("setInterface(alt=${captureInterface.alternateSetting}) failed")
            }

            val handle = NativeUsbReader.startStream(
                fd = connection.fileDescriptor,
                endpoint = endpoint.address,
                packetBytes = endpoint.maxPacketSize,
                packetsPerUrb = PACKETS_PER_URB,
                urbCount = URB_COUNT,
                frameBytes = profile.frameBytes,
                outChannels = profile.outChannels,
            )
            if (handle == 0L) {
                throw CaptureException("startStream failed: ${NativeUsbReader.streamError(0L)}")
            }

            Log.i(
                TAG,
                "${profile.label} stream started intf=${captureInterface.id} " +
                    "alt=${captureInterface.alternateSetting} " +
                    "ep=0x${endpoint.address.toString(16)} " +
                    "outChannels=${profile.outChannels}"
            )

            try {
                val pollBuffer = ByteArray(POLL_BUFFER_BYTES)
                while (shouldContinue()) {
                    val bytes = NativeUsbReader.pollStream(handle, pollBuffer)
                    when {
                        bytes < 0 ->
                            throw CaptureException("pollStream: ${NativeUsbReader.streamError(handle)}")
                        bytes == 0 -> Thread.sleep(2)
                        else -> onPcm(pollBuffer, bytes)
                    }
                }
            } finally {
                NativeUsbReader.stopStream(handle)
            }
        } finally {
            try {
                connection.releaseInterface(captureInterface)
            } catch (_: Throwable) {
            }
            connection.close()
        }
    }

    private fun findCaptureInterface(device: UsbDevice): UsbInterface? {
        val candidates = (0 until device.interfaceCount).map { device.getInterface(it) }
        return candidates.firstOrNull { intf ->
            intf.id == 2 && intf.alternateSetting == 1 && findIsoInEndpoint(intf) != null
        } ?: candidates.firstOrNull { intf ->
            intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO &&
                intf.interfaceSubclass == 2 &&
                findIsoInEndpoint(intf) != null
        }
    }

    private fun findIsoInEndpoint(intf: UsbInterface): UsbEndpoint? =
        (0 until intf.endpointCount)
            .map { intf.getEndpoint(it) }
            .firstOrNull { ep ->
                ep.type == UsbConstants.USB_ENDPOINT_XFER_ISOC &&
                    ep.direction == UsbConstants.USB_DIR_IN
            }

    companion object {
        // miniDSP's own VID — UMA-8.
        const val VENDOR_ID_MINIDSP = 0x2752
        // XMOS Xcore200 / MCHStreamer Lite VID — UMA-16 v2.
        const val VENDOR_ID_XMOS = 0x20B1

        const val SAMPLE_RATE_HZ = 48_000

        private const val PACKETS_PER_URB = 8
        private const val URB_COUNT = 32          // ~32 ms kernel buffering
        // UMA-16 frames are 2x the size of UMA-8 frames; size the poll buffer for
        // the larger array so a single drain holds plenty of margin.
        private const val POLL_BUFFER_BYTES = 128 * 1024

        private const val TAG = "VuedCapture"

        /** Device-id heuristic: match either VID or the product name. */
        fun isMicArrayDevice(device: UsbDevice): Boolean {
            val name = device.productName.orEmpty()
            return device.vendorId == VENDOR_ID_MINIDSP ||
                device.vendorId == VENDOR_ID_XMOS ||
                name.contains("micArray", ignoreCase = true) ||
                name.contains("UMA", ignoreCase = true) ||
                name.contains("MCHStreamer", ignoreCase = true)
        }

        /** Pick the array profile that matches this device's VID and product string. */
        fun profileFor(device: UsbDevice): MicArrayProfile {
            val name = device.productName.orEmpty()
            val isUma16 = device.vendorId == VENDOR_ID_XMOS ||
                name.contains("UMA-16", ignoreCase = true) ||
                name.contains("UMA16", ignoreCase = true) ||
                name.contains("MCHStreamer", ignoreCase = true)
            return if (isUma16) PROFILE_UMA16 else PROFILE_UMA8
        }
    }
}

/**
 * Capture geometry per supported miniDSP array. Both stream 24-bit samples in
 * 32-bit LE subslots at 48 kHz over UAC2 isochronous IN; they differ only in how
 * many channel slots ride each device frame and how many are real mics.
 */
data class MicArrayProfile(
    val label: String,
    val frameSlots: Int,
    val outChannels: Int,
) {
    /** 4-byte (32-bit LE) subslot per channel; 24-bit sample sits in the low bytes. */
    val frameBytes: Int get() = frameSlots * 4
}

val PROFILE_UMA8 = MicArrayProfile("UMA-8", frameSlots = 8, outChannels = 7)
val PROFILE_UMA16 = MicArrayProfile("UMA-16", frameSlots = 16, outChannels = 16)
