package com.nsn8.vued.capture

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.util.Log

/**
 * Discovers, claims, and streams raw PCM from the UMA-8 USB mic array.
 *
 * The native layer emits interleaved [OUT_CHANNELS] int32 little-endian samples per
 * frame at [SAMPLE_RATE_HZ]. [streamPcm] blocks on the calling thread, invoking
 * `onPcm(buffer, length)` with a reused buffer — the consumer must process each
 * callback synchronously before returning (no cross-thread retention).
 */
class Uma8Capture(context: Context) {

    private val usbManager: UsbManager =
        context.getSystemService(UsbManager::class.java)

    class CaptureException(message: String) : Exception(message)

    /** Returns the connected UMA-8 (or null), without requiring permission. */
    fun findDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == VENDOR_ID ||
                it.productName?.contains("micArray", ignoreCase = true) == true
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
            ?: throw CaptureException("UMA-8 not connected")
        if (!usbManager.hasPermission(device)) {
            throw CaptureException("USB permission not granted for UMA-8")
        }
        val captureInterface = findCaptureInterface(device)
            ?: throw CaptureException("No UMA-8 IN isochronous interface found")
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
                frameBytes = FRAME_BYTES,
                outChannels = OUT_CHANNELS,
            )
            if (handle == 0L) {
                throw CaptureException("startStream failed: ${NativeUsbReader.streamError(0L)}")
            }

            Log.i(TAG, "UMA-8 stream started intf=${captureInterface.id} " +
                "alt=${captureInterface.alternateSetting} ep=0x${endpoint.address.toString(16)}")

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
        const val VENDOR_ID = 0x2752

        // Confirmed by descriptor + native probe: 8 channel slots/frame (7 real mics
        // + 1 always-zero slot), 24-bit samples in 32-bit LE containers, 48 kHz.
        const val FRAME_SLOTS = 8
        const val OUT_CHANNELS = 7
        const val FRAME_BYTES = FRAME_SLOTS * 4
        const val SAMPLE_RATE_HZ = 48_000

        private const val PACKETS_PER_URB = 8
        private const val URB_COUNT = 32          // ~32 ms kernel buffering
        private const val POLL_BUFFER_BYTES = 64 * 1024

        private const val TAG = "VuedCapture"
    }
}
