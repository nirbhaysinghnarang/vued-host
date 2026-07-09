package com.nsn8.vued.audio

import android.os.StatFs
import com.nsn8.vued.DiagnosticsLogger
import java.io.File

/**
 * Last-line disk protection for the rolling audio rings: when the filesystem
 * drops below a free-space floor, the oldest segments are sacrificed so the
 * recorder can keep writing. Upload-driven deletion and the 72h age prune are
 * the primary reapers; this only fires when both have fallen behind (offline
 * backlog, runaway retention, etc.).
 */
object DiskSpaceGuard {
    const val DEFAULT_FLOOR_BYTES: Long = 5L * 1024 * 1024 * 1024

    /**
     * Available bytes on [directory]'s filesystem; Long.MAX_VALUE when the
     * lookup is unavailable (e.g. JVM unit tests), which disables the floor.
     */
    fun freeBytes(directory: File): Long =
        runCatching { StatFs(directory.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)

    /**
     * Deletes oldest-first `<startSec>[extension]` files (never [protect])
     * until [freeBytes] >= [floorBytes]. Returns the number deleted.
     */
    fun enforceFloor(
        directory: File,
        extension: String,
        protect: File?,
        floorBytes: Long = DEFAULT_FLOOR_BYTES,
        freeBytes: () -> Long,
    ): Int {
        if (freeBytes() >= floorBytes) return 0
        val candidates = directory.listFiles { file -> file.name.endsWith(extension) }
            ?.filter { it != protect }
            ?.sortedBy { it.nameWithoutExtension.toLongOrNull() ?: Long.MAX_VALUE }
            ?: return 0
        var deleted = 0
        var freedBytes = 0L
        for (file in candidates) {
            if (freeBytes() >= floorBytes) break
            val size = file.length()
            if (runCatching { file.delete() }.getOrDefault(false)) {
                deleted += 1
                freedBytes += size
            }
        }
        if (deleted > 0) {
            // runCatching: DiagnosticsLogger touches android.util.Log/org.json,
            // which throw in pure-JVM unit tests.
            runCatching {
                DiagnosticsLogger.warn(
                    "rolling_buffer_low_disk_pruned",
                    mapOf(
                        "dir" to directory.name,
                        "deleted" to deleted,
                        "freedBytes" to freedBytes,
                        "floorBytes" to floorBytes,
                    ),
                )
            }
        }
        return deleted
    }
}
