package com.nsn8.vued.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide observable recorder status, published by [RecordingService] and
 * collected by the UI. (A simple singleton is enough for Phase 1; a bound-service or
 * repository layer can replace it later.)
 */
object RecorderState {

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
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = Status()
    }
}
