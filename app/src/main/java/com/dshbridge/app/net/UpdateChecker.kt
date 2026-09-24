package com.dshbridge.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 版本更新：查最新版本 → 下载 APK。
 *
 * ## 为什么要多条通路
 *
 * 单靠 GitHub API 在国内基本不可用，实测（2026-09）踩到两类失败：
 *
 *  1. **API 限流**：未认证请求为 60 次/小时（按 IP 计）。运营商 CGNAT 或 VPN 共享出口极易打满，
 *     表现为 `HTTP 403`（注意：403 说明请求其实通了，只是被限流）。
 *  2. **域名不可达**：`github.com` / `api.github.com` 直连不通。
 *
 * 因此取版本号改为**多通路并行竞速**（谁先成功用谁，其余立即取消）：
 *
 *  | 通路 | 配额 | 说明 |
 *  |---|---|---|
 *  | `github.com/<repo>/releases/latest` 重定向 | 无 | 从 Location 头读 tag，最省 |
 *  | `github.com/<repo>/releases.atom` | 无 | Atom 源，仍是 github.com |
 *  | `api.github.com` | 60/h/IP | 信息最全（含更新说明、附件真实直链与大小）|
 *  | 镜像代理的 API | — | 镜像出口 IP，**同时绕开域名封锁与限流** |
 *
 * APK 下载则**逐级降级**：直连 → 各镜像前缀。
 *
 * ## 关于镜像的可信度
 *
 * 镜像只用于"公开的版本信息"与"下载安装包"。安装包即使被投毒也装不上：
 * Android 在安装时会校验签名，必须与已安装应用同一证书；本 App 另外还会比对发布信息里的
 * 文件大小。因此镜像不能把恶意 APK 装进设备。
 */
object UpdateChecker {

    private const val REPO = "stein1888/dsh-bridge-app"
    private const val ASSET_PREFIX = "dsh-bridge-app-"
    private const val GITHUB = "https://github.com"
    private const val API = "https://api.github.com"

    /** GitHub 加速镜像，仅作兜底。顺序即优先级。 */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/",
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    /** 只用于 `releases/latest`：不跟随重定向，才能从 Location 头读出 tag */
    private val noRedirectClient = client.newBuilder()
        .followRedirects(false)
        .build()

    /** 下载用：读超时放宽（安装包数 MB），但整体仍设上限 */
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()

    data class Release(
        /** 去掉前缀 v 的版本号，如 1.0.3 */
        val version: String,
        /** Release 正文（更新说明）；重定向/Atom 通路为空 */
        val notes: String,
        /** APK 附件直链 */
        val apkUrl: String,
        val apkName: String,
        /** 附件大小；未知为 -1 */
        val apkSize: Long,
    )

    private class VersionSource(val name: String, val fetch: suspend () -> Release)

    // ---- 查询最新版本 ----

    suspend fun fetchLatest(): Result<Release> = coroutineScope {
        val sources = versionSources()
        val channel = Channel<Pair<String, Result<Release>>>(sources.size)

        val jobs = sources.map { source ->
            launch(Dispatchers.IO) {
                channel.send(source.name to runCatching { source.fetch() })
            }
        }

        val failures = mutableListOf<String>()
        try {
            repeat(sources.size) {
                val (name, result) = channel.receive()
                val release = result.getOrNull()
                if (release != null) {
                    // 谁先成功用谁，其余立刻取消 —— 国内镜像通常 1~2 秒就回来，
                    // 不必等直连那几条把超时耗满
                    return@coroutineScope Result.success(release)
                }
                failures += "$name → ${result.exceptionOrNull()?.message ?: "失败"}"
            }
        } finally {
            jobs.forEach { it.cancel() }
            channel.close()
        }

        Result.failure(IllegalStateException(failures.joinToString("\n")))
    }

    private fun versionSources(): List<VersionSource> = buildList {
        add(VersionSource("releases/latest 重定向") {
            fetchViaRedirect("$GITHUB/$REPO/releases/latest")
        })
        add(VersionSource("releases.atom") {
            fetchViaAtom("$GITHUB/$REPO/releases.atom")
        })
        add(VersionSource("GitHub API") {
            fetchViaApi("$API/repos/$REPO/releases/latest")
        })
        MIRROR_PREFIXES.forEach { prefix ->
            val label = prefix.removePrefix("https://").trimEnd('/')
            add(VersionSource("$label 代 API") {
                fetchViaApi("$prefix$API/repos/$REPO/releases/latest")
            })
        }
    }

    /** 通路一：`/releases/latest` 会 302 到 `/releases/tag/vX.Y.Z`，从 Location 读出版本 */
    private fun fetchViaRedirect(url: String): Release {
        val request = Request.Builder().url(url).build()
        noRedirectClient.newCall(request).execute().use { response ->
            val location = response.header("Location")
                ?: error("HTTP ${response.code}，无重定向")
            val tag = location.trimEnd('/').substringAfterLast('/')
            val version = normalizeVersion(tag)
            if (version.isBlank()) error("无法从重定向解析版本号")
            return releaseFromConvention(tag, version, notes = "")
        }
    }

    /** 通路二：Atom 源，第一个 `releases/tag/...` 即最新版（无 API 配额） */
    private fun fetchViaAtom(url: String): Release {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val tag = TAG_IN_ATOM.find(text)?.groupValues?.getOrNull(1)
                ?: error("Atom 中没有 release 条目")
            val version = normalizeVersion(tag)
            if (version.isBlank()) error("无法解析版本号")
            return releaseFromConvention(tag, version, notes = "")
        }
    }

    /** 通路三/四：GitHub API（直连或镜像代理），能拿到更新说明与附件真实信息 */
    private fun fetchViaApi(url: String): Release {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // 把服务端说明带出来：403 若是"限流"，body 里会写明，便于区分限流与网络拦截
                val hint = runCatching { JSONObject(text).optString("message") }
                    .getOrNull()?.takeIf { it.isNotBlank() }
                error("HTTP ${response.code}" + (hint?.let { "（$it）" } ?: ""))
            }

            val json = JSONObject(text)
            val tag = json.optString("tag_name")
            val version = normalizeVersion(tag)
            if (version.isBlank()) error("响应缺少 tag_name")

            var apkUrl = ""
            var apkName = ""
            var apkSize = -1L
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

            val fallback = releaseFromConvention(tag, version, json.optString("body"))
            return if (apkUrl.isBlank()) fallback else fallback.copy(
                apkUrl = apkUrl,
                apkName = apkName.ifBlank { fallback.apkName },
                apkSize = apkSize,
            )
        }
    }

    /** 按发布约定拼附件直链：releases/download/<tag>/dsh-bridge-app-<version>.apk */
    private fun releaseFromConvention(tag: String, version: String, notes: String): Release {
        val normalizedTag = tag.trim().ifBlank { "v$version" }
        return Release(
            version = version,
            notes = notes,
            apkUrl = "$GITHUB/$REPO/releases/download/$normalizedTag/$ASSET_PREFIX$version.apk",
            apkName = "$ASSET_PREFIX$version.apk",
            apkSize = -1L,
        )
    }

    private fun normalizeVersion(tag: String): String =
        tag.trim().removePrefix("v").removePrefix("V")

    // ---- 下载 ----

    /**
     * 下载到 [dest]，逐级降级：直连 github.com → 各镜像前缀。
     *
     * @param originalUrl 发布信息里的直链（github.com 上的 releases/download/...）
     * @param expectedSize 期望字节数，>0 时校验（防止装到截断的包）；-1 表示未知
     * @param onProgress 主线程回调百分比，-1 表示服务端未给 Content-Length
     */
    suspend fun download(
        originalUrl: String,
        dest: File,
        expectedSize: Long = -1L,
        onProgress: (Int) -> Unit,
    ): File {
        val candidates = buildList {
            add(originalUrl)
            MIRROR_PREFIXES.forEach { add("$it$originalUrl") }
        }

        val failures = mutableListOf<String>()
        for (url in candidates) {
            try {
                return downloadFrom(url, dest, expectedSize, onProgress)
            } catch (t: Throwable) {
                failures += "${hostOf(url)} → ${t.message ?: t.javaClass.simpleName}"
                dest.delete()
            }
        }
        throw IllegalStateException(failures.joinToString("\n"))
    }

    private suspend fun downloadFrom(
        url: String,
        dest: File,
        expectedSize: Long,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()

        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/octet-stream")
            .build()

        downloadClient.newCall(request).execute().use { response ->
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

    private fun hostOf(url: String): String =
        runCatching { java.net.URI(url).host ?: url }.getOrDefault(url)

    /** 从 Atom 里取第一个 releases/tag/<tag> */
    private val TAG_IN_ATOM = Regex("""releases/tag/([^"<\s]+)""")

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
}
