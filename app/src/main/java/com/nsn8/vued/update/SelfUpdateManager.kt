package com.nsn8.vued.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageInfo
import android.os.Build
import android.util.Log
import com.nsn8.vued.BuildConfig
import com.nsn8.vued.DiagnosticsLogger
import com.nsn8.vued.VuedConfig
import com.nsn8.vued.meeting.MeetingController
import com.nsn8.vued.service.RecorderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

object SelfUpdateManager {
    const val ACTION_INSTALL_RESULT = "com.nsn8.vued.update.INSTALL_RESULT"
    private const val TAG = "VuedSelfUpdate"

    private val client = OkHttpClient()

    suspend fun installLatest(
        context: Context,
        onProgress: (String) -> Unit = {},
    ): UpdateResult = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        ensureInstallSafe()
        val manifestUrl = manifestUrl()
        onProgress("Checking for updates")
        Log.i(TAG, "check_started manifestUrl=$manifestUrl currentVersionName=${BuildConfig.VERSION_NAME} currentVersionCode=${BuildConfig.VERSION_CODE}")
        DiagnosticsLogger.info("self_update_check_started", mapOf("manifestUrl" to manifestUrl))

        val manifest = try {
            fetchManifest(manifestUrl, appContext.packageName)
        } catch (upToDate: UpdateCheckUpToDate) {
            Log.i(TAG, "check_treated_as_up_to_date reason=${upToDate.message}")
            DiagnosticsLogger.info(
                "self_update_check_treated_as_up_to_date",
                mapOf("reason" to upToDate.message),
            )
            return@withContext UpdateResult.UpToDate(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }
        currentCoroutineContext().ensureActive()
        Log.i(
            TAG,
            "manifest_loaded channel=${manifest.channel} package=${manifest.packageName} " +
                "versionName=${manifest.versionName} versionCode=${manifest.versionCode} " +
                "sizeBytes=${manifest.sizeBytes} url=${manifest.url}",
        )
        DiagnosticsLogger.info(
            "self_update_manifest_loaded",
            mapOf(
                "versionName" to manifest.versionName,
                "versionCode" to manifest.versionCode,
                "channel" to manifest.channel,
                "sizeBytes" to manifest.sizeBytes,
            ),
        )

        if (manifest.versionCode <= BuildConfig.VERSION_CODE) {
            Log.i(TAG, "not_available currentVersionCode=${BuildConfig.VERSION_CODE} latestVersionCode=${manifest.versionCode}")
            DiagnosticsLogger.info(
                "self_update_not_available",
                mapOf(
                    "currentVersionCode" to BuildConfig.VERSION_CODE,
                    "latestVersionCode" to manifest.versionCode,
                ),
            )
            return@withContext UpdateResult.UpToDate(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }

        ensureInstallSafe()
        onProgress("Downloading update ${manifest.versionName}")
        Log.i(TAG, "download_started url=${manifest.url}")
        val apk = try {
            downloadApk(appContext, manifest)
        } catch (upToDate: UpdateCheckUpToDate) {
            Log.i(TAG, "download_treated_as_up_to_date reason=${upToDate.message}")
            DiagnosticsLogger.info(
                "self_update_download_treated_as_up_to_date",
                mapOf("reason" to upToDate.message),
            )
            return@withContext UpdateResult.UpToDate(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        }
        currentCoroutineContext().ensureActive()
        try {
            currentCoroutineContext().ensureActive()
            ensureInstallSafe()
            onProgress("Preparing install")
            Log.i(TAG, "install_started apk=${apk.absolutePath} apkBytes=${apk.length()} targetVersionCode=${manifest.versionCode}")
            installApk(appContext, apk, manifest)
            DiagnosticsLogger.info(
                "self_update_install_committed",
                mapOf(
                    "versionName" to manifest.versionName,
                    "versionCode" to manifest.versionCode,
                ),
            )
            UpdateResult.Installing(manifest.versionName, manifest.versionCode)
        } finally {
            apk.delete()
        }
    }

    private fun ensureInstallSafe() {
        val recording = RecorderState.state.value.running
        val meetingActive = MeetingController.active != null
        if (recording || meetingActive) {
            throw IllegalStateException("Stop recording before updating.")
        }
    }

    private fun manifestUrl(): String =
        "${VuedConfig.UPDATE_RELEASE_BASE_URL.trimEnd('/')}/${VuedConfig.UPDATE_CHANNEL.trim('/')}/manifest.json"

    private fun fetchManifest(url: String, expectedPackageName: String): UpdateManifest {
        val request = Request.Builder().url(url).get().build()
        client.newCall(request).execute().use { response ->
            Log.i(TAG, "manifest_response http=${response.code} contentLength=${response.body?.contentLength()}")
            if (response.code == 400) {
                throw UpdateCheckUpToDate("manifest returned HTTP 400")
            }
            if (!response.isSuccessful) {
                throw IllegalStateException("Update manifest request failed: HTTP ${response.code}")
            }
            val body = response.body?.string().orEmpty()
            return parseManifest(JSONObject(body), expectedPackageName)
        }
    }

    private fun parseManifest(json: JSONObject, expectedPackageName: String): UpdateManifest {
        val artifact = json.optJSONArray("artifacts")
            ?.let { artifacts ->
                (0 until artifacts.length())
                    .mapNotNull { artifacts.optJSONObject(it) }
                    .firstOrNull { artifact ->
                        artifact.optString("platform") == "android" &&
                            artifact.optString("packageName", expectedPackageName) == expectedPackageName
                    }
            }
        val packageName = json.optString("packageName", artifact?.optString("packageName", expectedPackageName) ?: expectedPackageName)
        if (packageName != expectedPackageName) {
            throw IllegalStateException("Manifest packageName $packageName does not match $expectedPackageName")
        }

        val versionName = json.optString("versionName")
            .ifBlank { json.optString("version") }
            .ifBlank { artifact?.optString("versionName").orEmpty() }
            .ifBlank { throw IllegalStateException("Update manifest is missing versionName") }
        val versionCode = json.optLong("versionCode", artifact?.optLong("versionCode", -1L) ?: -1L)
        if (versionCode <= 0L) throw IllegalStateException("Update manifest is missing versionCode")

        val url = json.optString("url")
            .ifBlank { json.optString("apkUrl") }
            .ifBlank { artifact?.optString("url").orEmpty() }
            .ifBlank { throw IllegalStateException("Update manifest is missing APK url") }
        val sha256 = json.optString("sha256")
            .ifBlank { artifact?.optString("sha256").orEmpty() }
            .lowercase(Locale.US)
        if (sha256.isBlank()) throw IllegalStateException("Update manifest is missing sha256")

        return UpdateManifest(
            versionName = versionName,
            versionCode = versionCode,
            channel = json.optString("channel", VuedConfig.UPDATE_CHANNEL),
            packageName = packageName,
            url = url,
            sha256 = sha256,
            sizeBytes = json.optLong("sizeBytes", artifact?.optLong("sizeBytes", -1L) ?: -1L).takeIf { it > 0L },
        )
    }

    private fun downloadApk(context: Context, manifest: UpdateManifest): File {
        val apk = File.createTempFile("vued-update-", ".apk", context.cacheDir)
        val request = Request.Builder().url(manifest.url).get().build()
        client.newCall(request).execute().use { response ->
            Log.i(TAG, "apk_response http=${response.code} contentLength=${response.body?.contentLength()}")
            if (response.code == 400) {
                apk.delete()
                throw UpdateCheckUpToDate("download returned HTTP 400")
            }
            if (!response.isSuccessful) {
                apk.delete()
                throw IllegalStateException("APK download failed: HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("APK download response was empty")
            val digest = MessageDigest.getInstance("SHA-256")
            body.byteStream().use { input ->
                apk.outputStream().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                apk.delete()
                Log.e(TAG, "apk_checksum_mismatch expected=${manifest.sha256} actual=$actual")
                throw IllegalStateException("APK checksum mismatch")
            }
            Log.i(TAG, "apk_downloaded file=${apk.absolutePath} bytes=${apk.length()} sha256=$actual")
            DiagnosticsLogger.info(
                "self_update_apk_downloaded",
                mapOf(
                    "versionName" to manifest.versionName,
                    "versionCode" to manifest.versionCode,
                    "sizeBytes" to apk.length(),
                    "sha256" to actual,
                ),
            )
            return apk
        }
    }

    private fun installApk(appContext: Context, apk: File, manifest: UpdateManifest) {
        val installer = appContext.packageManager.packageInstaller
        val archiveInfo = appContext.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
        val installedInfo = runCatching { appContext.packageManager.getPackageInfo(appContext.packageName, 0) }.getOrNull()
        Log.i(
            TAG,
            "package_info installed=${installedInfo?.packageName} installedVersionName=${installedInfo?.versionName} " +
                "installedVersionCode=${installedInfo?.longVersionCodeCompat()} " +
                "archive=${archiveInfo?.packageName} archiveVersionName=${archiveInfo?.versionName} " +
                "archiveVersionCode=${archiveInfo?.longVersionCodeCompat()}",
        )
        DiagnosticsLogger.info(
            "self_update_package_info",
            mapOf(
                "installedPackageName" to installedInfo?.packageName,
                "installedVersionName" to installedInfo?.versionName,
                "installedVersionCode" to installedInfo?.longVersionCodeCompat(),
                "archivePackageName" to archiveInfo?.packageName,
                "archiveVersionName" to archiveInfo?.versionName,
                "archiveVersionCode" to archiveInfo?.longVersionCodeCompat(),
            ),
        )
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setAppPackageName(appContext.packageName)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                }
            }
        val sessionId = installer.createSession(params)
        Log.i(TAG, "install_session_created sessionId=$sessionId")
        val session = installer.openSession(sessionId)
        try {
            session.use {
                apk.inputStream().use { input ->
                    it.openWrite("vued-host-${manifest.versionCode}.apk", 0, apk.length()).use { output ->
                        input.copyTo(output)
                        it.fsync(output)
                    }
                }
                Log.i(TAG, "install_session_written sessionId=$sessionId bytes=${apk.length()}")
                val callbackIntent = Intent(ACTION_INSTALL_RESULT)
                    .setClass(appContext, UpdateInstallReceiver::class.java)
                    .putExtra("versionName", manifest.versionName)
                    .putExtra("versionCode", manifest.versionCode)
                    .putExtra("sessionId", sessionId)
                val pendingIntent = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    callbackIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                it.commit(pendingIntent.intentSender)
                Log.i(TAG, "install_session_committed sessionId=$sessionId")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "install_session_failed sessionId=$sessionId message=${error.message}", error)
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private data class UpdateManifest(
        val versionName: String,
        val versionCode: Long,
        val channel: String,
        val packageName: String,
        val url: String,
        val sha256: String,
        val sizeBytes: Long?,
    )

    sealed interface UpdateResult {
        data class UpToDate(val versionName: String, val versionCode: Int) : UpdateResult
        data class Installing(val versionName: String, val versionCode: Long) : UpdateResult
    }

    private class UpdateCheckUpToDate(message: String) : Exception(message)
}

private fun PackageInfo.longVersionCodeCompat(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
