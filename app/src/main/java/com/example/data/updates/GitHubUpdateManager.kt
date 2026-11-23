package com.example.data.updates

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class GitHubReleaseInfo(
    val repoSlug: String,
    val tagName: String,
    val versionDisplay: String,
    val releaseName: String,
    val changelog: String,
    val publishedAt: String,
    val htmlUrl: String,
    val apkAssetName: String?,
    val apkDownloadUrl: String?,
    val apkSizeBytes: Long,
    val isNewer: Boolean
)

sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class UpToDate(
        val currentVersion: String,
        val currentBuild: Int,
        val latestTag: String,
        val htmlUrl: String,
        val recentReleases: List<GitHubReleaseInfo> = emptyList()
    ) : UpdateCheckState

    data class UpdateAvailable(
        val release: GitHubReleaseInfo,
        val recentReleases: List<GitHubReleaseInfo> = emptyList()
    ) : UpdateCheckState

    data class Error(val message: String) : UpdateCheckState
}

sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState
    data class Downloading(
        val releaseTag: String,
        val fileName: String,
        val downloadedBytes: Long,
        val totalBytes: Long,
        val downloadId: Long
    ) : UpdateDownloadState {
        val progress: Float?
            get() = if (totalBytes > 0L) {
                (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            } else {
                null
            }
    }

    data class Completed(
        val releaseTag: String,
        val fileName: String,
        val downloadId: Long
    ) : UpdateDownloadState

    data class Failed(
        val releaseTag: String,
        val message: String
    ) : UpdateDownloadState
}

data class DownloadPollSnapshot(
    val status: Int,
    val bytesDownloaded: Long,
    val bytesTotal: Long,
    val reason: Int
)

class GitHubUpdateManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkLatestRelease(rawRepoInput: String): Result<Pair<GitHubReleaseInfo, List<GitHubReleaseInfo>>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val slug = normalizeRepoSlug(rawRepoInput)
                    ?: throw IllegalArgumentException(
                        "Invalid GitHub repository. Use 'owner/repo' or 'https://github.com/owner/repo'."
                    )

                val recent = fetchRecentReleasesJson(slug, limit = 6)
                    .map { parseRelease(slug, it) }

                val latestFromEndpoint = fetchLatestReleaseJson(slug)?.let { parseRelease(slug, it) }
                val latest = latestFromEndpoint
                    ?: recent.firstOrNull()
                    ?: throw IOException("No published GitHub Releases found in $slug yet.")

                val mergedRecent = buildList {
                    add(latest)
                    recent.filterNot { it.tagName == latest.tagName }.forEach { add(it) }
                }.take(6)

                latest to mergedRecent
            }
        }

    fun enqueueApkDownload(
        context: Context,
        release: GitHubReleaseInfo
    ): Result<Pair<Long, String>> = runCatching {
        val url = release.apkDownloadUrl
            ?.takeIf { isTrustedGitHubUrl(it) }
            ?: throw IllegalStateException("This GitHub release does not contain a direct APK asset.")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw UnsupportedOperationException("DIRECT_BROWSER_FALLBACK")
        }

        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            ?: throw IllegalStateException("Android DownloadManager unavailable.")

        val rawName = release.apkAssetName?.takeIf { it.lowercase().endsWith(".apk") }
            ?: "LocalMind-${release.tagName}.apk"
        val safeFileName = rawName.replace(Regex("[^A-Za-z0-9._-]"), "_")

        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("LocalMind ${release.tagName}")
            .setDescription("In-place APK update · settings & models preserved")
            .setMimeType("application/vnd.android.package-archive")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(true)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, safeFileName)

        val downloadId = dm.enqueue(request)
        downloadId to safeFileName
    }

    fun queryDownloadStatus(context: Context, downloadId: Long): DownloadPollSnapshot? {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return null
        val query = DownloadManager.Query().setFilterById(downloadId)
        return runCatching {
            dm.query(query)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                val dlIdx = cursor.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                val totalIdx = cursor.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                val reasonIdx = cursor.getColumnIndex(DownloadManager.COLUMN_REASON)
                DownloadPollSnapshot(
                    status = if (statusIdx >= 0) cursor.getInt(statusIdx) else DownloadManager.STATUS_FAILED,
                    bytesDownloaded = if (dlIdx >= 0) cursor.getLong(dlIdx).coerceAtLeast(0L) else 0L,
                    bytesTotal = if (totalIdx >= 0) cursor.getLong(totalIdx).coerceAtLeast(0L) else 0L,
                    reason = if (reasonIdx >= 0) cursor.getInt(reasonIdx) else 0
                )
            }
        }.getOrNull()
    }

    fun cancelDownload(context: Context, downloadId: Long) {
        val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager ?: return
        runCatching { dm.remove(downloadId) }
    }

    private fun fetchLatestReleaseJson(slug: String): JSONObject? {
        val latestUrl = "https://api.github.com/repos/$slug/releases/latest"
        executeGitHubJson(latestUrl).let { (code, body) ->
            if (code in 200..299 && body.isNotBlank()) {
                return JSONObject(body)
            }
            if (code != 404) {
                throw IOException(formatGitHubError(code, body))
            }
        }
        return null
    }

    private fun fetchRecentReleasesJson(slug: String, limit: Int): List<JSONObject> {
        val listUrl = "https://api.github.com/repos/$slug/releases?per_page=$limit"
        val (code, body) = executeGitHubJson(listUrl)
        if (code !in 200..299) {
            if (code == 404) return emptyList()
            throw IOException(formatGitHubError(code, body))
        }
        val array = JSONArray(body)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (!item.optBoolean("draft", false)) {
                    add(item)
                }
            }
        }
    }

    private fun executeGitHubJson(url: String): Pair<Int, String> {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LocalMind-Android/${BuildConfig.VERSION_NAME}")
            .build()

        return client.newCall(req).execute().use { resp ->
            resp.code to resp.body?.string().orEmpty()
        }
    }

    private fun parseRelease(slug: String, json: JSONObject): GitHubReleaseInfo {
        val tagName = json.optString("tag_name").trim().ifBlank { "v1.1.0" }
        val releaseName = json.optString("name").trim().ifBlank { tagName }
        val changelog = json.optString("body").trim()
        val publishedAt = json.optString("published_at").trim().take(10)

        val rawHtmlUrl = json.optString("html_url").trim()
        val safeHtmlUrl = if (isTrustedGitHubUrl(rawHtmlUrl)) {
            rawHtmlUrl
        } else {
            "https://github.com/$slug/releases"
        }

        // Prefer arm64 / LocalMind APK assets when multiple exist
        val apkAssets = mutableListOf<Triple<String, String, Long>>()
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").trim()
                val dl = asset.optString("browser_download_url").trim()
                if (name.lowercase().endsWith(".apk") && isTrustedGitHubUrl(dl)) {
                    apkAssets += Triple(name, dl, asset.optLong("size", 0L))
                }
            }
        }
        val preferredApk = apkAssets.firstOrNull { (name, _, _) ->
            val l = name.lowercase()
            l.contains("arm64") || l.contains("localmind")
        } ?: apkAssets.firstOrNull()

        val isNewer = isTagNewerThanCurrent(
            remoteTag = tagName,
            currentVersionName = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE
        )

        return GitHubReleaseInfo(
            repoSlug = slug,
            tagName = tagName,
            versionDisplay = tagName.removePrefix("v").removePrefix("V"),
            releaseName = releaseName,
            changelog = changelog,
            publishedAt = publishedAt,
            htmlUrl = safeHtmlUrl,
            apkAssetName = preferredApk?.first,
            apkDownloadUrl = preferredApk?.second,
            apkSizeBytes = preferredApk?.third ?: 0L,
            isNewer = isNewer
        )
    }

    private fun formatGitHubError(code: Int, body: String): String {
        val msg = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        return when (code) {
            403 -> "GitHub API rate limit reached. Try again in a few minutes."
            404 -> "Repository or Releases not found on GitHub (must be public)."
            else -> "GitHub API HTTP $code${if (msg.isNotBlank()) ": $msg" else ""}"
        }
    }

    companion object {
        private val REPO_SLUG_REGEX = Regex("^[A-Za-z0-9._-]+/[A-Za-z0-9._-]+$")
        private val NUMBER_TOKEN_REGEX = Regex("\\d+")

        fun openSystemDownloads(context: Context): Boolean {
            return runCatching {
                val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        fun openTrustedUrlInBrowser(context: Context, url: String): Boolean {
            if (!isTrustedGitHubUrl(url)) return false
            return runCatching {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
            }.getOrDefault(false)
        }

        fun normalizeRepoSlug(raw: String): String? {
            var cleaned = raw.trim()
            if (cleaned.isEmpty()) return null
            cleaned = cleaned
                .removePrefix("https://github.com/")
                .removePrefix("http://github.com/")
                .removePrefix("github.com/")
                .removeSuffix(".git")
                .trim('/')

            val parts = cleaned.split('/').filter { it.isNotBlank() }
            if (parts.size < 2) return null
            val candidate = "${parts[0]}/${parts[1]}"
            return candidate.takeIf { REPO_SLUG_REGEX.matches(it) }
        }

        fun isTrustedGitHubUrl(url: String): Boolean {
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            val host = parsed.host.lowercase()
            return host == "github.com" ||
                host.endsWith(".github.com") ||
                host == "githubusercontent.com" ||
                host.endsWith(".githubusercontent.com")
        }

        fun isTagNewerThanCurrent(
            remoteTag: String,
            currentVersionName: String,
            currentVersionCode: Int
        ): Boolean {
            val trimmedTag = remoteTag.trim()
            if (trimmedTag.isEmpty()) return false

            // Support GitHub Actions build tags like "apk-build-12"
            if (trimmedTag.lowercase().startsWith("apk-build-")) {
                val buildNum = trimmedTag.substringAfterLast('-').toIntOrNull() ?: return false
                return buildNum > currentVersionCode
            }

            val remoteNums = extractVersionNumbers(trimmedTag)
            val localNums = extractVersionNumbers(currentVersionName)
            if (remoteNums.isEmpty()) return false

            val maxLen = maxOf(remoteNums.size, localNums.size)
            for (i in 0 until maxLen) {
                val r = remoteNums.getOrElse(i) { 0 }
                val l = localNums.getOrElse(i) { 0 }
                if (r != l) return r > l
            }
            return false
        }

        private fun extractVersionNumbers(version: String): List<Int> =
            NUMBER_TOKEN_REGEX.findAll(version)
                .mapNotNull { it.value.toIntOrNull() }
                .take(4)
                .toList()
    }
}
