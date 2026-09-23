package com.dshbridge.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * dsh-bridge 的访问认证。
 *
 * 登录是一个干净的 JSON 接口（见 dsh-bridge `lib/index.js` 的 ProxyServer）：
 *
 *   POST /__dsh_bridge__/login   body: {"password":"..."}
 *     200 + Set-Cookie: dsh_bridge_auth=<sessionToken>; Path=/; HttpOnly; SameSite=Lax; Max-Age=2592000
 *     401 + {"ok":false,"error":"访问密码错误"}
 *
 * 因此"记住密码"不需要在网页里模拟填表：这里原生 POST 拿到会话令牌，再交给
 * WebView 的 CookieManager 注入，页面就直接是已登录状态 —— 不依赖登录页 DOM 结构，
 * 宿主改版也不会失效。
 */
object BridgeAuth {

    private val JSON = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    sealed interface Result {
        /** 登录成功，携带会话令牌（即 Set-Cookie 里 dsh_bridge_auth 的值） */
        data class Ok(val sessionToken: String) : Result

        /** 密码被服务端明确拒绝（401） */
        data class Unauthorized(val message: String) : Result

        /** 网络/协议层面失败，不代表密码错 */
        data class Failed(val message: String) : Result
    }

    /**
     * 用访问密码换取会话令牌。
     * @param pageUrl 该链接的页面地址（从它推导出 origin，从而支持隧道域名与局域网 IP）
     */
    suspend fun login(pageUrl: String, password: String): Result = withContext(Dispatchers.IO) {
        val origin = originOf(pageUrl) ?: return@withContext Result.Failed("地址无效")
        val payload = JSONObject().put("password", password).toString()
        val request = Request.Builder()
            .url("$origin$LOGIN_PATH")
            .post(payload.toRequestBody(JSON))
            .header("Accept", "application/json")
            .build()

        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val token = response.headers("Set-Cookie").firstNotNullOfOrNull(::sessionTokenOf)
                    if (token != null) {
                        Result.Ok(token)
                    } else {
                        Result.Failed("服务器未返回会话 Cookie")
                    }
                } else {
                    val serverMessage = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    val message = serverMessage?.takeIf { it.isNotBlank() }
                        ?: "登录失败（HTTP ${response.code}）"
                    if (response.code == 401) Result.Unauthorized(message) else Result.Failed(message)
                }
            }
        } catch (t: Throwable) {
            Result.Failed(t.message ?: "网络错误")
        }
    }

    /** `scheme://host:port`；HttpUrl 会补全默认端口，便于直接拼接登录路径 */
    fun originOf(pageUrl: String): String? {
        val url = pageUrl.toHttpUrlOrNull() ?: return null
        return "${url.scheme}://${url.host}:${url.port}"
    }

    private fun sessionTokenOf(setCookie: String): String? =
        COOKIE_PATTERN.find(setCookie)?.groupValues?.getOrNull(1)

    private const val LOGIN_PATH = "/__dsh_bridge__/login"
    private val COOKIE_PATTERN = Regex("dsh_bridge_auth=([A-Za-z0-9_-]+)")
}
