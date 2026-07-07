package com.nsn8.vued

import android.content.Context
import android.media.MediaMetadataRetriever
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nsn8.vued.auth.VuedAuth
import com.nsn8.vued.net.OutboundQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /**
     * A meeting enqueued AFTER an ambient backlog must still upload first (priority
     * tiers). Needs a signed-in device and the dev backend; use multi-MB m4a files so
     * the backlog outlives the meeting upload.
     *
     *   adb shell am instrument -w \
     *     -e class com.nsn8.vued.OutboundQueueStressTest#drainPrioritizesMeetingOverAmbientBacklog \
     *     -e audioPath /data/local/tmp/vued-stress -e ambientCount 5 \
     *     com.nsn8.vued.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun drainPrioritizesMeetingOverAmbientBacklog() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        val ambientCount = args.getString("ambientCount")?.toIntOrNull()?.let { max(2, it) } ?: 5
        val timeoutMs = args.getString("timeoutMs")?.toLongOrNull() ?: 10 * 60_000L

        VuedAuth.init(context)
        waitForToken(args.getString("authTimeoutMs")?.toLongOrNull() ?: 15_000L)
        clearOutboundQueue(context.filesDir)

        val sources = resolveSources(File(requireNotNull(args.getString("audioPath")) {
            "Pass -e audioPath /path/to/file.m4a or /path/to/directory"
        }))
        require(sources.isNotEmpty()) { "No .m4a files found" }

        val ambientIds = (0 until ambientCount).map { enqueueAmbientFrom(context, sources[it % sources.size], it) }
        val meetingIds = enqueueMeetingPair(context, sources.first())

        val job = launch(Dispatchers.IO) { OutboundQueue.drain(context) }
        val ambientLeft = pollUntilRemoved(context, meetingIds, timeoutMs) { pending ->
            pending.count { it in ambientIds }
        }
        assertTrue("Meeting items did not drain within ${timeoutMs}ms", ambientLeft >= 0)
        assertTrue(
            "Meeting drained only after the whole ambient backlog — priority not applied",
            ambientLeft > 0,
        )
        job.join()
    }

    /**
     * A meeting enqueued while a drain is mid-backlog must be picked up by that drain
     * (per-item queue reload + rerun flag), not wait for the next trigger.
     *
     *   adb shell am instrument -w \
     *     -e class com.nsn8.vued.OutboundQueueStressTest#drainPicksUpMeetingEnqueuedMidDrain \
     *     -e audioPath /data/local/tmp/vued-stress -e ambientCount 5 \
     *     com.nsn8.vued.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun drainPicksUpMeetingEnqueuedMidDrain() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        val ambientCount = args.getString("ambientCount")?.toIntOrNull()?.let { max(3, it) } ?: 5
        val timeoutMs = args.getString("timeoutMs")?.toLongOrNull() ?: 10 * 60_000L

        VuedAuth.init(context)
        waitForToken(args.getString("authTimeoutMs")?.toLongOrNull() ?: 15_000L)
        clearOutboundQueue(context.filesDir)

        val sources = resolveSources(File(requireNotNull(args.getString("audioPath")) {
            "Pass -e audioPath /path/to/file.m4a or /path/to/directory"
        }))
        require(sources.isNotEmpty()) { "No .m4a files found" }

        val ambientIds = (0 until ambientCount).map { enqueueAmbientFrom(context, sources[it % sources.size], it) }

        val job = launch(Dispatchers.IO) { OutboundQueue.drain(context) }
        val firstGone = pollUntilRemoved(context, listOf(ambientIds.first()), timeoutMs) { 0 }
        assertTrue("First ambient item did not drain within ${timeoutMs}ms", firstGone >= 0)

        val meetingIds = enqueueMeetingPair(context, sources.first())
        OutboundQueue.drain(context) // returns immediately or reruns — exercises the rerun flag

        val ambientLeft = pollUntilRemoved(context, meetingIds, timeoutMs) { pending ->
            pending.count { it in ambientIds }
        }
        assertTrue("Mid-drain meeting did not drain within ${timeoutMs}ms", ambientLeft >= 0)
        assertTrue(
            "Mid-drain meeting drained only after the whole ambient backlog",
            ambientLeft > 0,
        )
        job.join()
    }

    /**
     * With every upload failing (run signed out or in airplane mode), a drain must
     * terminate — each item attempted at most once per pass, gate-skips included —
     * and leave the queue intact. No backend or audioPath needed (synthetic files).
     *
     *   adb shell am instrument -w \
     *     -e class com.nsn8.vued.OutboundQueueStressTest#drainTerminatesWithFailingItems \
     *     com.nsn8.vued.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun drainTerminatesWithFailingItems() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        val timeoutMs = args.getString("timeoutMs")?.toLongOrNull() ?: 60_000L

        VuedAuth.init(context)
        clearOutboundQueue(context.filesDir)
        try {
            val fakeMeeting = fakeM4a(context, "term_meeting")
            enqueueMeetingPair(context, fakeMeeting, copySource = false)
            repeat(2) { index ->
                enqueueAmbientFrom(context, fakeM4a(context, "term_ambient_$index"), index, copySource = false)
            }
            val seeded = OutboundQueue.size(context)

            withTimeout(timeoutMs) { OutboundQueue.drain(context) }

            val after = OutboundQueue.size(context)
            assertTrue(
                "Expected all $seeded items still pending offline, found $after — " +
                    "run this test signed out or in airplane mode",
                after == seeded,
            )
        } finally {
            clearOutboundQueue(context.filesDir) // never leave synthetic items in the real queue
        }
    }

    /**
     * A large ambient source-WAV upload must yield between chunks to a meeting
     * enqueued mid-upload, then resume. Needs a signed-in device and the dev backend.
     *
     *   adb shell am instrument -w \
     *     -e class com.nsn8.vued.OutboundQueueStressTest#ambientSourceWavYieldsToMeeting \
     *     -e audioPath /data/local/tmp/vued-stress -e sourceWavMb 64 \
     *     com.nsn8.vued.test/androidx.test.runner.AndroidJUnitRunner
     */
    @Test
    fun ambientSourceWavYieldsToMeeting() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val args = InstrumentationRegistry.getArguments()
        val sourceWavMb = args.getString("sourceWavMb")?.toIntOrNull()?.let { max(16, it) } ?: 64
        val uploadHeadStartMs = args.getString("uploadHeadStartMs")?.toLongOrNull() ?: 3_000L
        val timeoutMs = args.getString("timeoutMs")?.toLongOrNull() ?: 5 * 60_000L

        VuedAuth.init(context)
        waitForToken(args.getString("authTimeoutMs")?.toLongOrNull() ?: 15_000L)
        clearOutboundQueue(context.filesDir)
        try {
            val sources = resolveSources(File(requireNotNull(args.getString("audioPath")) {
                "Pass -e audioPath /path/to/file.m4a or /path/to/directory"
            }))
            require(sources.isNotEmpty()) { "No .m4a files found" }

            val sliceId = UUID.randomUUID().toString().replace("-", "")
            val startedAtSec = System.currentTimeMillis() / 1000.0
            val wav = writeSilentSourceWav(context, sourceWavMb)
            val enqueued = OutboundQueue.enqueueAmbientSourceWav(
                context = context,
                sliceId = sliceId,
                sessionId = UUID.randomUUID().toString().replace("-", ""),
                startedAtSec = startedAtSec,
                endedAtSec = startedAtSec + 300.0,
                durationSecs = 300.0,
                monoSizeBytes = 1L,
                source = wav,
            )
            requireNotNull(enqueued) { "Ambient source wav was dropped by the backlog cap" }
            val sourceItemId = "source:$sliceId"

            val job = launch(Dispatchers.IO) { OutboundQueue.drain(context) }
            delay(uploadHeadStartMs) // let the chunked upload get underway

            val meetingIds = enqueueMeetingPair(context, sources.first())
            OutboundQueue.drain(context)

            val sidecarStillPending = pollUntilRemoved(context, meetingIds, timeoutMs) { pending ->
                if (sourceItemId in pending) 1 else 0
            }
            assertTrue("Meeting items did not drain within ${timeoutMs}ms", sidecarStillPending >= 0)
            assertTrue(
                "Ambient source wav finished before the meeting — it did not yield",
                sidecarStillPending > 0,
            )
            withTimeout(timeoutMs) { job.join() }
        } finally {
            clearOutboundQueue(context.filesDir) // remove the synthetic sidecar + leftovers
        }
    }

    // ---- helpers for the priority tests ----

    /** Ids currently pending in the raw persisted index (same store the queue uses). */
    private fun pendingIds(context: Context): Set<String> {
        val raw = context.getSharedPreferences("vued_outbound", Context.MODE_PRIVATE)
            .getString("queue", "[]")
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("id") }.toSet()
    }

    /**
     * Polls until every id in [watched] has left the queue, then returns
     * [onRemoved] evaluated against that same snapshot; -1 on timeout.
     */
    private suspend fun pollUntilRemoved(
        context: Context,
        watched: List<String>,
        timeoutMs: Long,
        onRemoved: (Set<String>) -> Int,
    ): Int {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val pending = pendingIds(context)
            if (watched.none { it in pending }) return onRemoved(pending)
            delay(50)
        }
        return -1
    }

    /** Enqueues one ambient slice from [source]; returns its queue item id. */
    private fun enqueueAmbientFrom(
        context: Context,
        source: File,
        index: Int,
        copySource: Boolean = true,
    ): String {
        val sliceId = UUID.randomUUID().toString().replace("-", "")
        val staged = if (copySource) {
            File(context.cacheDir, "prio_ambient_${sliceId}.m4a").also { source.copyTo(it, overwrite = true) }
        } else source
        val durationSecs = if (copySource) readDurationSecs(staged) else 300.0
        val startedAtSec = System.currentTimeMillis() / 1000.0 + index
        OutboundQueue.enqueueAmbient(
            context = context,
            sliceId = sliceId,
            sessionId = UUID.randomUUID().toString().replace("-", ""),
            startedAtSec = startedAtSec,
            endedAtSec = startedAtSec + durationSecs,
            durationSecs = durationSecs,
            source = staged,
        )
        return sliceId
    }

    /** Enqueues a meeting create + audio slice; returns both queue item ids. */
    private fun enqueueMeetingPair(
        context: Context,
        source: File,
        copySource: Boolean = true,
    ): List<String> {
        val meetingId = UUID.randomUUID().toString().replace("-", "")
        val sliceId = UUID.randomUUID().toString().replace("-", "")
        val staged = if (copySource) {
            File(context.cacheDir, "prio_meeting_${sliceId}.m4a").also { source.copyTo(it, overwrite = true) }
        } else source
        val durationSecs = if (copySource) readDurationSecs(staged) else 60.0
        val startedAtSec = System.currentTimeMillis() / 1000.0
        OutboundQueue.enqueueMeetingCreate(
            context = context,
            meetingId = meetingId,
            title = "Priority Test Meeting",
            startedAtSec = startedAtSec,
        )
        OutboundQueue.enqueueMeeting(
            context = context,
            sliceId = sliceId,
            sessionId = UUID.randomUUID().toString().replace("-", ""),
            meetingId = meetingId,
            startedAtSec = startedAtSec,
            endedAtSec = startedAtSec + durationSecs,
            durationSecs = durationSecs,
            source = staged,
        )
        return listOf("meeting:$meetingId", sliceId)
    }

    /** Tiny non-audio placeholder file — only used where nothing actually uploads. */
    private fun fakeM4a(context: Context, name: String): File =
        File(context.cacheDir, "$name.m4a").apply { writeBytes(ByteArray(1024)) }

    /** A valid 16-ch/16-kHz PCM16 WAV of ~[megabytes] MB of silence. */
    private fun writeSilentSourceWav(context: Context, megabytes: Int): File {
        val channels = 16
        val sampleRate = 16_000
        val blockAlign = channels * 2
        val dataBytes = (megabytes.toLong() * 1024 * 1024 / blockAlign) * blockAlign
        val file = File(context.cacheDir, "prio_source_${System.currentTimeMillis()}.wav")
        RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            header.put("RIFF".toByteArray())
            header.putInt((36 + dataBytes).toInt())
            header.put("WAVE".toByteArray())
            header.put("fmt ".toByteArray())
            header.putInt(16)
            header.putShort(1) // PCM
            header.putShort(channels.toShort())
            header.putInt(sampleRate)
            header.putInt(sampleRate * blockAlign)
            header.putShort(blockAlign.toShort())
            header.putShort(16) // bits per sample
            header.put("data".toByteArray())
            header.putInt(dataBytes.toInt())
            raf.write(header.array())
            raf.setLength(44 + dataBytes) // extends with zeros = silence
        }
        return file
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
