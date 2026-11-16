package com.aboezzat.vpn.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

data class AppUpdateInfo(
    val versionCode: Long,
    val versionName: String,
    val title: String,
    val notes: String,
    val downloadUrl: String
)

object GitHubReleaseUpdater {
    private const val RELEASE_API =
        "https://api.github.com/repos/mohamedezzat19/AboEzzat-VPN/releases/latest"
    private const val APK_ASSET = "AboEzzat-VPN.apk"

    fun checkForUpdate(context: Context): AppUpdateInfo? {
        val connection = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "AboEzzat-VPN-Android")
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("GitHub update check failed: HTTP ${connection.responseCode}")
            }
            val release = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(release)
            if (json.optBoolean("draft", false) || json.optBoolean("prerelease", false)) return null

            val tag = json.optString("tag_name")
            val notes = json.optString("body")
            val versionCode = Regex("(?im)^\\s*versionCode\\s*:\\s*(\\d+)\\s*$")
                .find(notes)?.groupValues?.get(1)?.toLongOrNull()
                ?: Regex("(\\d+)$").find(tag)?.groupValues?.get(1)?.toLongOrNull()
                ?: return null
            val versionName = tag.removePrefix("v").ifBlank { "Unknown" }
            val assets = json.optJSONArray("assets") ?: return null
            var downloadUrl: String? = null
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                if (asset.optString("name") == APK_ASSET) {
                    downloadUrl = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                    break
                }
            }
            val url = downloadUrl ?: return null
            val installedVersion = if (Build.VERSION.SDK_INT >= 28) {
                context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toLong()
            }
            if (versionCode <= installedVersion) return null
            return AppUpdateInfo(
                versionCode = versionCode,
                versionName = versionName,
                title = json.optString("name").ifBlank { "AboEzzat VPN $versionName" },
                notes = notes,
                downloadUrl = url
            )
        } finally {
            connection.disconnect()
        }
    }

    fun download(
        context: Context,
        update: AppUpdateInfo,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): File {
        val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "AboEzzat-VPN-Android")
            setRequestProperty("Accept", "application/octet-stream")
        }
        val output = File(context.cacheDir, "updates").apply { mkdirs() }
        val apk = File(output, APK_ASSET)
        val temporary = File(output, "$APK_ASSET.part")
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("APK download failed: HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            connection.inputStream.use { input ->
                FileOutputStream(temporary).use { fileOutput ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        fileOutput.write(buffer, 0, count)
                        downloaded += count
                        onProgress(downloaded, total)
                    }
                    fileOutput.fd.sync()
                }
            }
            if (downloaded <= 0L || (total > 0L && downloaded != total)) {
                throw IllegalStateException("Downloaded APK size is incomplete.")
            }
            if (apk.exists() && !apk.delete()) throw IllegalStateException("Could not replace cached APK.")
            if (!temporary.renameTo(apk)) throw IllegalStateException("Could not finalize downloaded APK.")
            return apk
        } catch (error: Exception) {
            temporary.delete()
            throw error
        } finally {
            connection.disconnect()
        }
    }
}
