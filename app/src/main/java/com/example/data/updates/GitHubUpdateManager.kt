package com.example.data.updates

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
        val latestTag: String,
        val htmlUrl: String
    ) : UpdateCheckState

    data class UpdateAvailable(
        val release: GitHubReleaseInfo
    ) : UpdateCheckState

    data class Error(val message: String) : UpdateCheckState
}

class GitHubUpdateManager {

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    suspend fun checkLatestRelease(rawRepoInput: String): Result<GitHubReleaseInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                val slug = normalizeRepoSlug(rawRepoInput)
                    ?: throw IllegalArgumentException(
                        "Invalid GitHub repository. Use 'owner/repo' or 'https://github.com/owner/repo'."
                    )

                val releaseJson = fetchLatestReleaseJson(slug)
                    ?: throw IOException("No published GitHub Releases found in $slug yet.")

                parseRelease(slug, releaseJson)
            }
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

        // Fallback when repository only has prereleases or workflow build releases
        val listUrl = "https://api.github.com/repos/$slug/releases?per_page=5"
        val (code, body) = executeGitHubJson(listUrl)
        if (code !in 200..299) {
            throw IOException(formatGitHubError(code, body))
        }
        val array = JSONArray(body)
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            if (!item.optBoolean("draft", false)) {
                return item
            }
        }
        return null
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
        val tagName = json.optString("tag_name").trim().ifBlank { "v1.0" }
        val releaseName = json.optString("name").trim().ifBlank { tagName }
        val changelog = json.optString("body").trim()
        val publishedAt = json.optString("published_at").trim().take(10)

        val rawHtmlUrl = json.optString("html_url").trim()
        val safeHtmlUrl = if (isTrustedGitHubUrl(rawHtmlUrl)) {
            rawHtmlUrl
        } else {
            "https://github.com/$slug/releases"
        }

        var apkName: String? = null
        var apkUrl: String? = null
        var apkSize = 0L
        val assets = json.optJSONArray("assets")
        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name").trim()
                val dl = asset.optString("browser_download_url").trim()
                if (name.lowercase().endsWith(".apk") && isTrustedGitHubUrl(dl)) {
                    apkName = name
                    apkUrl = dl
                    apkSize = asset.optLong("size", 0L)
                    break
                }
            }
        }

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
            apkAssetName = apkName,
            apkDownloadUrl = apkUrl,
            apkSizeBytes = apkSize,
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
