package com.dshbridge.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 版本更新：查 GitHub 最新 Release → 取 APK 附件 → 下载。
 *
 * 之所以走 GitHub Releases API 而不是"下载某个版本文件"：发布流程本身就是
 * 打 tag + 上传签名 APK（见仓库 README），API 能同时给出**版本号、更新说明、附件直链与大小**，
 * 不需要额外维护版本清单文件。
 */
object UpdateChecker {

    private const val REPO = "stein1888/dsh-bridge-app"

    /** 发布约定：Release 附件名固定为 dsh-bridge-app-<version>.apk（兜底通路据此拼直链） */
    private const val ASSET_PREFIX = "dsh-bridge-app-"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** 探版本专用：不要自动跟随重定向，才能从 Location 头里读出 tag */
    private val probeClient = OkHttpClient.Builder()
        .followRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    data class Release(
        /** 去掉前缀 v 的版本号，如 1.0.3 */
        val version: String,
        /** Release 正文（更新说明），兜底通路下为空 */
        val notes: String,
        /** APK 附件直链；没有附件时为空串 */
        val apkUrl: String,
        val apkName: String,
        val apkSize: Long,
    )

    /**
     * 查询最新版本。
     *
     * 两条通路，API 优先、失败自动降级：
     *
     *  1. **GitHub Releases API**：能一次拿到版本号、更新说明、附件的真实直链与大小。
     *     但未认证请求的限额是 **60 次/小时/IP**，运营商 CGNAT 共享出口很容易被打满
     *     （实测就遇到过 403 rate limit），所以不能只依赖它。
     *  2. **`/releases/latest` 的重定向**：GitHub 会 302 到 `/releases/tag/vX.Y.Z`，
     *     从 Location 头就能读出最新版本号 —— 这条通路**没有速率限制**，
     *     再按发布约定拼出附件直链。
     */
    suspend fun fetchLatest(): Result<Release> = withContext(Dispatchers.IO) {
        val viaApi = runCatching { fetchViaApi() }
        if (viaApi.isSuccess) return@withContext viaApi

        val viaRedirect = runCatching { fetchViaRedirect() }
        if (viaRedirect.isSuccess) return@withContext viaRedirect

        // 两条都失败时，报 API 那条的错误（信息更具体，例如 HTTP 403 限流）
        Result.failure(
            viaApi.exceptionOrNull()
                ?: viaRedirect.exceptionOrNull()
                ?: IllegalStateException("未知错误"),
        )
    }

    private fun fetchViaApi(): Release {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPO/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")

            val json = JSONObject(text)
            val tag = json.optString("tag_name")

            var apkUrl = ""
            var apkName = ""
            var apkSize = 0L
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkUrl = asset.optString("browser_download_url")
                        apkName = name
                        apkSize = asset.optLong("size")
                        break
                    }
                }
            }

            val version = normalizeVersion(tag)
            if (version.isBlank()) error("响应缺少 tag_name")
            if (apkUrl.isBlank()) {
                apkUrl = conventionAssetUrl(tag, version)
                apkName = "$ASSET_PREFIX$version.apk"
            }

            return Release(
                version = version,
                notes = json.optString("body"),
                apkUrl = apkUrl,
                apkName = apkName,
                apkSize = apkSize,
            )
        }
    }

    private fun fetchViaRedirect(): Release {
        val request = Request.Builder()
            .url("https://github.com/$REPO/releases/latest")
            .build()

        probeClient.newCall(request).execute().use { response ->
            // 200 说明没有 release（GitHub 在无 release 时直接返回 releases 页）
            val location = response.header("Location") ?: error("没有重定向，可能尚无 Release")
            val tag = location.trimEnd('/').substringAfterLast('/')
            val version = normalizeVersion(tag)
            if (version.isBlank()) error("无法从重定向解析版本号")

            return Release(
                version = version,
                notes = "",
                apkUrl = conventionAssetUrl(tag, version),
                apkName = "$ASSET_PREFIX$version.apk",
                apkSize = -1L,
            )
        }
    }

    /** 按发布约定拼附件直链：releases/download/<tag>/dsh-bridge-app-<version>.apk */
    private fun conventionAssetUrl(tag: String, version: String): String {
        val normalizedTag = tag.trim().ifBlank { "v$version" }
        return "https://github.com/$REPO/releases/download/$normalizedTag/$ASSET_PREFIX$version.apk"
    }

    private fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")

    /** 语义化比较：remote 比 current 新才返回 true。无法解析时保守返回 false。 */
    fun isNewer(remote: String, current: String): Boolean {
        val r = parseVersion(remote) ?: return false
        val c = parseVersion(current) ?: return false
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun parseVersion(version: String): List<Int>? =
        version.trim()
            .removePrefix("v")
            .removePrefix("V")
            // 去掉 -rc1 / +build 之类的后缀，只比数字段
            .split('-', '+', ' ')
            .first()
            .split('.')
            .mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.isNotEmpty() }

    /**
     * 下载到 [dest]。 [onProgress] 在主线程回调百分比（-1 表示服务端未给 Content-Length）。
     * 返回下载完成的文件；大小与 [expectedSize] 不符时抛错（避免装到截断的包）。
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSize: Long = -1L,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body ?: error("空响应")
            val total = if (body.contentLength() > 0) body.contentLength() else expectedSize

            var done = 0L
            var lastPercent = Int.MIN_VALUE

            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        val percent = if (total > 0) ((done * 100) / total).toInt() else -1
                        if (percent != lastPercent) {
                            lastPercent = percent
                            withContext(Dispatchers.Main) { onProgress(percent) }
                        }
                    }
                    output.flush()
                }
            }
        }

        if (expectedSize > 0 && dest.length() != expectedSize) {
            dest.delete()
            error("size-mismatch")
        }
        dest
    }
}
