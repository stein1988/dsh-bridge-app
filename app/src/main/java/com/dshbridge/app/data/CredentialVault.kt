package com.dshbridge.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 按链接保存机密信息：**访问密码**与**扫码免密 token**。
 *
 * 优先使用 Android Keystore 支撑的 EncryptedSharedPreferences（AES256-GCM 值加密 +
 * AES256-SIV 键加密）。若设备 Keystore 异常（极少见），退回普通 prefs，但把
 * [encrypted] 置为 false —— 由 UI 明确告知"凭据未加密"，绝不静默降级。
 *
 * 注意：访问密码用于调用 `POST /__dsh_bridge__/login`；免密 token 是二维码 URL 里
 * `?auth=` 的值，两者是不同机密，互不覆盖。
 */
class CredentialVault(context: Context) {

    private val appContext = context.applicationContext

    /** false 表示当前未加密存储（Keystore 不可用时的降级），UI 应据此提示用户 */
    val encrypted: Boolean

    private val prefs: SharedPreferences

    init {
        var created: SharedPreferences? = null
        var success = false
        try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            created = EncryptedSharedPreferences.create(
                appContext,
                FILE_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            success = true
        } catch (t: Throwable) {
            Log.w(TAG, "EncryptedSharedPreferences 不可用，退回普通存储", t)
        }
        prefs = created ?: appContext.getSharedPreferences(FILE_FALLBACK, Context.MODE_PRIVATE)
        encrypted = success
    }

    // ---- 访问密码 ----

    fun savePassword(linkId: String, password: String) {
        prefs.edit().putString(passwordKey(linkId), password).apply()
    }

    fun password(linkId: String): String? =
        prefs.getString(passwordKey(linkId), null)?.takeIf { it.isNotEmpty() }

    fun hasPassword(linkId: String): Boolean = password(linkId) != null

    fun clearPassword(linkId: String) {
        prefs.edit().remove(passwordKey(linkId)).apply()
    }

    // ---- 扫码免密 token ----

    fun saveToken(linkId: String, token: String) {
        prefs.edit().putString(tokenKey(linkId), token).apply()
    }

    fun token(linkId: String): String? =
        prefs.getString(tokenKey(linkId), null)?.takeIf { it.isNotEmpty() }

    // ---- 组合操作 ----

    /** 删除链接时清掉它名下的全部机密 */
    fun clearAll(linkId: String) {
        prefs.edit().remove(passwordKey(linkId)).remove(tokenKey(linkId)).apply()
    }

    private fun passwordKey(id: String) = "$id$SUFFIX_PASSWORD"
    private fun tokenKey(id: String) = "$id$SUFFIX_TOKEN"

    private companion object {
        const val TAG = "CredentialVault"
        const val FILE_ENCRYPTED = "credentials"
        const val FILE_FALLBACK = "credentials_plain"
        const val SUFFIX_PASSWORD = ":password"
        const val SUFFIX_TOKEN = ":token"
    }
}
