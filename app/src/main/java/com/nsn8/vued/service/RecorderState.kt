package com.nsn8.vued.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide observable recorder status, published by [RecordingService] and
 * collected by the UI. (A simple singleton is enough for Phase 1; a bound-service or
 * repository layer can replace it later.)
 */
object RecorderState {

    private val lock = Any()

    data class Status(
        val running: Boolean = false,
        val segmentCount: Int = 0,
        val lastSegment: String? = null,
        val sourceWavRecording: Boolean = false,
        val sourceWavSegmentCount: Int = 0,
        val lastSourceWavSegment: String? = null,
        val lastAudioMs: Long = 0L,
        val peakDb: Float = Float.NEGATIVE_INFINITY,
        val error: String? = null,
        val captureReady: Boolean = false,
        val micDisconnected: Boolean = false,
        val disconnectedAtMs: Long = 0L,
        val resumeOnReconnect: Boolean = false,
    ) {
        fun hasFreshAudio(nowMs: Long = System.currentTimeMillis()): Boolean =
            captureReady &&
                !micDisconnected &&
                lastAudioMs > 0L &&
                nowMs - lastAudioMs <= CAPTURE_STALE_MS
    }

    const val CAPTURE_STALE_MS = 5_000L

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state

    fun update(transform: (Status) -> Status) {
        synchronized(lock) {
            _state.value = transform(_state.value)
        }
    }

    fun reset() {
        synchronized(lock) {
            _state.value = Status()
        }
    }

    /**
     * Records the physical UMA mic becoming unavailable. Multiple signals can report
     * the same loss (USB detach, stream failure, or stale audio), so retain the first
     * timestamp and whether capture was active at that moment.
     */
    fun markMicDisconnected(
        disconnectedAtMs: Long = System.currentTimeMillis(),
        captureWasRunning: Boolean? = null,
    ) {
        synchronized(lock) {
            val current = _state.value
            val isFirstDisconnect = !current.micDisconnected || current.disconnectedAtMs <= 0L
            _state.value = current.copy(
                captureReady = false,
                error = "Mic disconnected",
                micDisconnected = true,
                disconnectedAtMs = if (isFirstDisconnect) disconnectedAtMs else current.disconnectedAtMs,
                resumeOnReconnect = if (isFirstDisconnect) {
                    captureWasRunning ?: current.running
                } else {
                    current.resumeOnReconnect
                },
            )
        }
    }

    /**
     * A user mute is distinct from a physical disconnect. It must not erase a pending
     * meeting timeout, but it must prevent capture from automatically resuming.
     */
    fun markCaptureStoppedByUser() {
        update {
            it.copy(
                running = false,
                captureReady = false,
                resumeOnReconnect = false,
            )
        }
    }

    /**
     * Clears the physical-loss flag as soon as the UMA device returns. Keep the
     * reconnect metadata until capture succeeds so existing auto-resume can use it.
     */
    fun markMicReconnected() {
        update {
            it.copy(
                captureReady = false,
                micDisconnected = false,
                error = null,
            )
        }
    }
}
