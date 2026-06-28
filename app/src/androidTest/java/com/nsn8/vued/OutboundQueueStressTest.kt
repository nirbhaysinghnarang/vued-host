package com.nsn8.vued

import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * Seeds the real outbound queue with large meeting audio slices and optionally drains
 * them to the dev backend.
 *
 * Push one or more M4A files to a device-readable path, then run this test with:
 *
 *   adb shell am instrument -w \
 *     -e class com.nsn8.vued.OutboundQueueStressTest#seedMeetingSlices \
 *     -e audioPath /data/local/tmp/vued-stress \
 *     -e count 10 \
 *     -e drain true \
 *     com.nsn8.vued.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class OutboundQueueStressTest {

    @Test
    fun seedMeetingSlices() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()

        val audioPath = requireNotNull(args.getString("audioPath")) {
            "Pass -e audioPath /path/to/file.m4a or /path/to/directory"
        }
        val count = args.getString("count")?.toIntOrNull()?.let { max(1, it) } ?: 1
        val drain = args.getString("drain")?.toBooleanStrictOrNull() ?: true
        val createRowsFirst = args.getString("createRowsFirst")?.toBooleanStrictOrNull() ?: true
        val clearExisting = args.getString("clearExisting")?.toBooleanStrictOrNull() ?: false
        val requireDrained = args.getString("requireDrained")?.toBooleanStrictOrNull() ?: drain
        val titlePrefix = args.getString("titlePrefix") ?: "Stress Meeting"
        val durationOverrideSecs = args.getString("durationSecs")?.toDoubleOrNull()
        val authTimeoutMs = args.getString("authTimeoutMs")?.toLongOrNull() ?: 15_000L

        VuedAuth.init(context)
        if (clearExisting) clearOutboundQueue(context.filesDir)
        if (createRowsFirst || drain) waitForToken(authTimeoutMs)

        val before = OutboundQueue.size(context)
        val sources = resolveSources(File(audioPath))
        require(sources.isNotEmpty()) { "No .m4a files found at $audioPath" }

        val sessionId = UUID.randomUUID().toString().replace("-", "")
        Log.i(TAG, "seed begin count=$count drain=$drain createRowsFirst=$createRowsFirst before=$before sources=${sources.size}")
        repeat(count) { index ->
            val source = sources[index % sources.size]
            val meetingId = UUID.randomUUID().toString().replace("-", "")
            val sliceId = UUID.randomUUID().toString().replace("-", "")
            val startedAtSec = System.currentTimeMillis() / 1000.0 + index
            val staged = File(context.cacheDir, "stress_${meetingId}_$index.m4a")

            source.copyTo(staged, overwrite = true)
            val durationSecs = durationOverrideSecs ?: readDurationSecs(staged)
            OutboundQueue.enqueueMeetingCreate(
                context = context,
                meetingId = meetingId,
                title = "$titlePrefix ${index + 1}",
                startedAtSec = startedAtSec,
            )
            if (createRowsFirst) {
                val created = OutboundQueue.drainMeetingCreate(context, meetingId)
                assertTrue("Meeting row was not created before audio enqueue for $meetingId", created)
                Log.i(TAG, "created meeting row index=${index + 1}/$count meeting=$meetingId")
            }
            OutboundQueue.enqueueMeeting(
                context = context,
                sliceId = sliceId,
                sessionId = sessionId,
                meetingId = meetingId,
                startedAtSec = startedAtSec,
                endedAtSec = startedAtSec + durationSecs,
                durationSecs = durationSecs,
                source = staged,
            )
            Log.i(
                TAG,
                "enqueued audio index=${index + 1}/$count meeting=$meetingId slice=$sliceId " +
                    "durationSecs=$durationSecs sizeBytes=${File(context.filesDir, "outbound/$sliceId.m4a").length()}",
            )
        }

        val seeded = OutboundQueue.size(context)
        val expectedNewQueueRecords = count * if (createRowsFirst) 1 else 2
        assertTrue(
            "Expected at least $expectedNewQueueRecords new queue records; before=$before after=$seeded",
            seeded >= before + expectedNewQueueRecords,
        )

        if (drain) {
            Log.i(TAG, "drain begin queued=$seeded")
            OutboundQueue.drain(context)
            val afterDrain = OutboundQueue.size(context)
            Log.i(TAG, "drain done before=$before seeded=$seeded after=$afterDrain")
            if (requireDrained) {
                assertTrue(
                    "Queue did not drain back to its starting size; before=$before after=$afterDrain",
                    afterDrain <= before,
                )
            }
        }
    }

    private fun resolveSources(path: File): List<File> =
        if (path.isDirectory) {
            path.listFiles { file -> file.isFile && file.extension.equals("m4a", ignoreCase = true) }
                ?.sortedBy { it.name }
                .orEmpty()
        } else {
            listOf(path).filter { it.isFile && it.extension.equals("m4a", ignoreCase = true) }
        }

    private fun readDurationSecs(file: File): Double {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: error("Could not read duration from ${file.absolutePath}; pass -e durationSecs <seconds>")
            durationMs / 1000.0
        } finally {
            retriever.release()
        }
    }

    private suspend fun waitForToken(timeoutMs: Long) {
        val started = System.currentTimeMillis()
        while (VuedAuth.currentAccessToken().isNullOrBlank()) {
            check(System.currentTimeMillis() - started < timeoutMs) {
                "No Supabase access token loaded. Sign in with the app first, or run with -e drain false."
            }
            delay(250)
        }
    }

    private fun clearOutboundQueue(filesDir: File) {
        filesDir.resolve("outbound").deleteRecursively()
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .getSharedPreferences("vued_outbound", android.content.Context.MODE_PRIVATE)
            .edit()
            .remove("queue")
            .commit()
    }

    private companion object {
        const val TAG = "VuedStressTest"
    }
}
