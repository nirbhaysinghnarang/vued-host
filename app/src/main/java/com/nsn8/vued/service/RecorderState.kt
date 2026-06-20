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
        val peakDb: Float = Float.NEGATIVE_INFINITY,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(Status())
    val state: StateFlow<Status> = _state

    fun update(transform: (Status) -> Status) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        _state.value = Status()
    }
}
