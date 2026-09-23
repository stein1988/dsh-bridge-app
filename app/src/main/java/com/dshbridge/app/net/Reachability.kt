package com.dshbridge.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 卡片上那个"可达 / 暂不可达"状态点。
 *
 * 判定标准刻意放宽：**只要服务端回了任何 HTTP 响应就算可达**（401/403/404 都算）——
 * dsh-bridge 开了访问认证时，未登录请求本来就会拿到 401，若把它当成不可达，
 * 状态点就永远是灰的，反而误导。只有网络层失败（DNS/连接超时/拒绝）才算不可达，
 * 那才真的说明"电脑不在线或地址不通"。
 */
object Reachability {

    private val client = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .callTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url).get().build()
            // 不读 body：只要拿到了响应头就立刻关闭连接
            client.newCall(request).execute().use { true }
        }.getOrDefault(false)
    }
}
