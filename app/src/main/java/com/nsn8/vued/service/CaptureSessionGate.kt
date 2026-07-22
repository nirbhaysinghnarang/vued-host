package com.nsn8.vued.service

import java.util.concurrent.atomic.AtomicLong

/**
 * Identifies the one capture session that is allowed to publish recorder state.
 *
 * Audio APIs can deliver a final buffer while a service is stopping. Fast
 * stop/start cycles can also leave an old capture thread finishing after a new
 * one has begun. A session must therefore still own the current generation at
 * the exact moment it publishes state; checking a boolean before processing a
 * buffer is not sufficient.
 */
internal class CaptureSessionGate {
    private val publicationLock = Any()
    private val generation = AtomicLong(0L)

    fun begin(): Long = synchronized(publicationLock) {
        generation.incrementAndGet()
    }

    fun isActive(sessionId: Long): Boolean = generation.get() == sessionId

    /** Invalidates whichever capture session is current, if any. */
    fun invalidateCurrent() {
        synchronized(publicationLock) {
            generation.incrementAndGet()
        }
    }

    /** Invalidates [sessionId] without disturbing a newer capture session. */
    fun invalidateIfActive(sessionId: Long): Boolean = synchronized(publicationLock) {
        if (!isActive(sessionId)) {
            false
        } else {
            generation.incrementAndGet()
            true
        }
    }

    /**
     * Runs [action] only while [sessionId] still owns publication rights.
     * Invalidation uses the same lock, so a completed stop cannot be followed by
     * a late state publication from the invalidated session.
     */
    fun runIfActive(sessionId: Long, action: () -> Unit): Boolean =
        synchronized(publicationLock) {
            if (!isActive(sessionId)) {
                false
            } else {
                action()
                true
            }
        }

    /**
     * Publishes terminal state for [sessionId] and invalidates it atomically.
     * A stale thread cannot finish a session that has already been replaced.
     */
    fun finishIfActive(sessionId: Long, action: () -> Unit): Boolean =
        synchronized(publicationLock) {
            if (!isActive(sessionId)) {
                false
            } else {
                action()
                generation.incrementAndGet()
                true
            }
        }
}
