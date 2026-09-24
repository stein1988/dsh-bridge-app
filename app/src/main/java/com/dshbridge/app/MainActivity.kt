package com.dshbridge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.dshbridge.app.data.CredentialVault
import com.dshbridge.app.data.LinkRecord
import com.dshbridge.app.data.LinkStore
import com.dshbridge.app.databinding.ActivityMainBinding
import com.dshbridge.app.databinding.DialogManualAddBinding
import com.dshbridge.app.databinding.DialogPasswordBinding
import com.dshbridge.app.databinding.DialogUpdateFailedBinding
import com.dshbridge.app.net.ApkInstaller
import com.dshbridge.app.net.BridgeAuth
import com.dshbridge.app.net.Reachability
import com.dshbridge.app.net.UpdateChecker
import com.dshbridge.app.net.WebViewCookies
import com.dshbridge.app.ui.Insets
import com.dshbridge.app.ui.LinkAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

/**
 * 首页：链接记录列表 + 两个添加入口（文本链接 / 扫码）+ 版本更新。
 *
 * 每条记录支持「记住密码 / 修改密码」「清除密码」：密码经 [BridgeAuth] 原生化验证通过后
 * 存进 [CredentialVault]（Keystore 加密），打开会话页时自动换取会话 cookie。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LinkStore
    private lateinit var vault: CredentialVault
    private lateinit var adapter: LinkAdapter

    /** 连通性探测结果缓存：id -> 结果与时间戳 */
    private data class Probe(val reachable: Boolean, val at: Long)

    private val probes = mutableMapOf<String, Probe>()
    private var probeJob: Job? = null

    private var latestRelease: UpdateChecker.Release? = null
    private var checkingUpdate = false
    private var downloadJob: Job? = null

    /** 扫码页返回结果（自带相机扫码与「从相册选择」两条路，都通过这个回传） */
    private val scanLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        val contents = result.data?.getStringExtra(ScanActivity.EXTRA_SCAN_RESULT)
        if (!contents.isNullOrBlank()) handleScanned(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applySystemBarsPadding(binding.root)

        store = LinkStore(this)
        vault = CredentialVault(this)

        adapter = LinkAdapter(
            onClick = { openLink(it) },
            onMenu = { anchor, record -> showItemMenu(anchor, record) },
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter

        binding.btnAddLink.setOnClickListener { showManualDialog() }
        binding.btnScan.setOnClickListener { launchScan() }
        binding.btnUpdate.setOnClickListener { onUpdateClicked() }
        binding.tvVersion.text = getString(R.string.version_label, BuildConfig.VERSION_NAME)

        if (!vault.encrypted) toast(getString(R.string.vault_plain_warning))

        // 启动时静默查一次最新版本。刻意加节流：API 配额按 IP 计，每次启动都查会很快
        // 把共享出口（CGNAT/VPN）的额度耗光，反而让手动检查也失败。
        maybeAutoCheckForUpdate()
    }

    override fun onResume() {
        super.onResume()
        renderList()
        startProbesIfNeeded()
    }

    // ---- 列表渲染 ----

    private fun renderList() {
        val items = store.all()
        val passwordIds = items.filter { vault.hasPassword(it.id) }.map { it.id }.toSet()
        val reachability = items.associate { it.id to probes[it.id]?.reachable }

        adapter.submit(items, reachability, passwordIds)

        val empty = items.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvSubtitle.text = subtitleFor(items, reachability)
    }

    private fun subtitleFor(
        items: List<LinkRecord>,
        reachability: Map<String, Boolean?>,
    ): String {
        if (items.isEmpty()) return getString(R.string.home_subtitle_empty)
        val known = reachability.values.filterNotNull()
        // 还有条目没出结果时显示"正在检测"，避免把"未知"当成"不可达"报给用户
        return if (known.size < items.size) {
            getString(R.string.home_subtitle_checking, items.size)
        } else {
            getString(R.string.home_subtitle_count, items.size, known.count { it })
        }
    }

    /**
     * 并发探测各链接的连通性。
     *
     * 结果有 TTL 缓存：每次回到首页都重打一遍网络既慢又费电，而连通性本来就是个
     * 变化很慢的量。每条探测一回来就重绘，用户看到的是状态点逐个亮起/变灰。
     */
    private fun startProbesIfNeeded() {
        if (probeJob?.isActive == true) return

        val now = System.currentTimeMillis()
        val stale = store.all().filter { record ->
            probes[record.id]?.let { now - it.at > PROBE_TTL_MS } != false
        }
        if (stale.isEmpty()) return

        probeJob = lifecycleScope.launch {
            stale.map { record ->
                launch {
                    val reachable = Reachability.probe(record.url)
                    probes[record.id] = Probe(reachable, System.currentTimeMillis())
                    renderList()
                }
            }.joinAll()
        }
    }

    // ---- 添加 ----

    private fun launchScan() {
        scanLauncher.launch(ScanActivity.intent(this))
    }

    private fun showManualDialog() {
        val dialogBinding = DialogManualAddBinding.inflate(layoutInflater)
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.manual_title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val url = normalizeInputUrl(dialogBinding.etUrl.text?.toString().orEmpty())
                if (url == null) {
                    dialogBinding.tilUrl.error = getString(R.string.invalid_url)
                    return@setOnClickListener
                }
                val name = dialogBinding.etName.text?.toString()?.trim().orEmpty()
                val record = store.addOrTouch(url, name)
                renderList()
                dialog.dismiss()
                openLink(record)
            }
        }
        dialog.show()
    }

    /**
     * 处理扫码结果。
     *
     * dsh-bridge 的二维码形如 `http://192.168.1.10:3082/?auth=<secretToken>`：
     * 这里把 `auth` / `token` 参数从**保存的地址**里摘出来单独加密保管，首页就不会把
     * 免密 token 明文显示出来；打开会话页时再拼回去。
     */
    private fun handleScanned(raw: String) {
        val url = normalizeInputUrl(raw)
        if (url == null) {
            toast(getString(R.string.invalid_url))
            return
        }
        val parsed = url.toHttpUrlOrNull()
        val authToken = parsed?.queryParameter("auth") ?: parsed?.queryParameter("token")

        val cleanUrl = parsed?.newBuilder()
            ?.removeAllQueryParameters("auth")
            ?.removeAllQueryParameters("token")
            ?.build()
            ?.toString()
            ?: url

        val record = store.addOrTouch(cleanUrl)
        if (!authToken.isNullOrBlank()) vault.saveToken(record.id, authToken)
        renderList()
        openLink(record)
    }

    /** 补全 scheme 并校验；返回规范化后的地址，非法返回 null */
    private fun normalizeInputUrl(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val parsed = withScheme.toHttpUrlOrNull() ?: return null
        if (parsed.host.isBlank()) return null
        return parsed.toString()
    }

    // ---- 打开 ----

    private fun openLink(record: LinkRecord) {
        store.touch(record.id)
        startActivity(WebViewActivity.intent(this, record.id))
    }

    // ---- 单项操作 ----

    private fun showItemMenu(anchor: View, record: LinkRecord) {
        val hasPassword = vault.hasPassword(record.id)
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_PASSWORD, 0, if (hasPassword) R.string.menu_update_password else R.string.menu_remember_password)
        if (hasPassword) {
            popup.menu.add(0, MENU_CLEAR_PASSWORD, 1, R.string.menu_clear_password)
        }
        popup.menu.add(0, MENU_COPY, 2, R.string.menu_copy)
        popup.menu.add(0, MENU_DELETE, 3, R.string.menu_delete)

        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_PASSWORD -> { showPasswordDialog(record); true }
                MENU_CLEAR_PASSWORD -> { confirmClearPassword(record); true }
                MENU_COPY -> { copyLink(record); true }
                MENU_DELETE -> { confirmDelete(record); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun confirmClearPassword(record: LinkRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_clear_password)
            .setMessage(R.string.clear_password_confirm)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                vault.clearPassword(record.id)
                toast(getString(R.string.password_cleared))
                renderList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun confirmDelete(record: LinkRecord) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.menu_delete)
            .setMessage(R.string.delete_confirm)
            .setPositiveButton(R.string.action_ok) { _, _ ->
                store.delete(record.id)
                vault.clearAll(record.id)
                probes.remove(record.id)
                toast(getString(R.string.link_deleted))
                renderList()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun copyLink(record: LinkRecord) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("dsh-bridge", record.url))
        toast(getString(R.string.link_copied))
    }

    // ---- 记住密码 ----

    private fun showPasswordDialog(record: LinkRecord) {
        val dialogBinding = DialogPasswordBinding.inflate(layoutInflater)
        val isUpdate = vault.hasPassword(record.id)
        dialogBinding.etPassword.setText(vault.password(record.id).orEmpty())

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (isUpdate) R.string.password_title_update else R.string.password_title_remember)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.action_ok, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val password = dialogBinding.etPassword.text?.toString().orEmpty()
                if (password.isEmpty()) {
                    showDialogError(dialogBinding, getString(R.string.password_empty))
                    return@setOnClickListener
                }
                positive.isEnabled = false
                dialogBinding.progress.visibility = View.VISIBLE
                dialogBinding.tvError.visibility = View.GONE
                verifyAndSave(record, password, dialog, dialogBinding)
            }
        }
        dialog.show()
    }

    /**
     * 先在服务端验证密码，再决定是否保存。三种结果区别对待：
     *  - 成功：保存并把会话 cookie 写进 WebView，随后打开即为已登录；
     *  - 401：**不保存**，明确告知密码错；
     *  - 网络失败：不能据此判定密码错，允许保存但如实提示"未能联网验证"。
     */
    private fun verifyAndSave(
        record: LinkRecord,
        password: String,
        dialog: AlertDialog,
        dialogBinding: DialogPasswordBinding,
    ) {
        lifecycleScope.launch {
            when (val result = BridgeAuth.login(record.url, password)) {
                is BridgeAuth.Result.Ok -> {
                    vault.savePassword(record.id, password)
                    WebViewCookies.applySession(record.url, result.sessionToken)
                    toast(getString(R.string.password_saved_ok))
                    dialog.dismiss()
                    renderList()
                }

                is BridgeAuth.Result.Unauthorized -> {
                    dialogBinding.progress.visibility = View.GONE
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                    showDialogError(dialogBinding, getString(R.string.password_wrong))
                }

                is BridgeAuth.Result.Failed -> {
                    vault.savePassword(record.id, password)
                    toast(getString(R.string.password_saved_unverified))
                    dialog.dismiss()
                    renderList()
                }
            }
        }
    }

    private fun showDialogError(dialogBinding: DialogPasswordBinding, message: String) {
        dialogBinding.tvError.text = message
        dialogBinding.tvError.visibility = View.VISIBLE
    }

    // ---- 版本更新 ----

    /** 点按钮：已查到新版本就直接开始下载安装，否则先查一次。 */
    private fun onUpdateClicked() {
        val release = latestRelease
        if (release != null && UpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME)) {
            downloadAndInstall(release)
        } else {
            checkForUpdate(userInitiated = true)
        }
    }

    /**
     * 启动时的静默检查，带节流（[AUTO_CHECK_INTERVAL_MS] 内最多一次）。
     * 手动点击不受此限制。
     */
    private fun maybeAutoCheckForUpdate() {
        val prefs = getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        val last = prefs.getLong(KEY_LAST_AUTO_CHECK, 0L)
        if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) return
        prefs.edit().putLong(KEY_LAST_AUTO_CHECK, System.currentTimeMillis()).apply()
        checkForUpdate(userInitiated = false)
    }

    /**
     * 查询最新版本。
     * @param userInitiated 自动检查（启动时）失败不打扰用户，只有手动点才提示结果
     */
    private fun checkForUpdate(userInitiated: Boolean) {
        if (checkingUpdate) return
        checkingUpdate = true
        binding.btnUpdate.isEnabled = false
        binding.btnUpdate.setText(R.string.update_checking)

        lifecycleScope.launch {
            val result = UpdateChecker.fetchLatest()
            checkingUpdate = false
            binding.btnUpdate.isEnabled = true

            result.onSuccess { release ->
                latestRelease = release
                if (UpdateChecker.isNewer(release.version, BuildConfig.VERSION_NAME)) {
                    binding.btnUpdate.setText(getString(R.string.update_available, release.version))
                } else {
                    binding.btnUpdate.setText(R.string.update_check)
                    if (userInitiated) toast(getString(R.string.update_latest))
                }
            }.onFailure { error ->
                binding.btnUpdate.setText(R.string.update_check)
                if (userInitiated) {
                    // 失败原因现在是逐条通路列出的多行文本，Toast 显示不下，用弹窗
                    showUpdateFailureDialog(error.message ?: error.javaClass.simpleName)
                }
            }
        }
    }

    /**
     * 更新失败的详情弹窗（自定义布局：竖排整宽按钮，避免默认按钮行把三个中文按钮挤到换行）。
     *
     * 两个调试手段：
     *  - **复制详情**：内容较长且含具体错误，方便原样贴出来排查；
     *  - **浏览器测试**：用同一个 URL 在系统浏览器里打开。
     *    若浏览器能拿到 JSON 而 App 失败，说明是"应用分流/分应用代理"没把本应用纳入代理，
     *    而非网络本身不通 —— 这是最快区分"App 问题"与"网络问题"的办法。
     */
    private fun showUpdateFailureDialog(detail: String) {
        val dialogBinding = DialogUpdateFailedBinding.inflate(layoutInflater)
        val contextLine = getString(R.string.update_failed_context, BuildConfig.VERSION_NAME)
        val clipboardText = "$contextLine\n\n${getString(R.string.update_failed_sources_label)}\n$detail"

        dialogBinding.tvContext.text = contextLine
        dialogBinding.tvDetail.text = detail

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCopyDetail.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("dsh-bridge update error", clipboardText))
            toast(getString(R.string.update_detail_copied))
        }
        dialogBinding.btnBrowserTest.setOnClickListener {
            val opened = runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.diagnosticUrl())))
            }.isSuccess
            if (!opened) toast(getString(R.string.update_open_failed))
        }
        dialogBinding.btnDismiss.setOnClickListener { dialog.dismiss() }

        dialog.show()
    }

    private fun downloadAndInstall(release: UpdateChecker.Release) {
        if (downloadJob?.isActive == true) return
        if (release.apkUrl.isBlank()) {
            toast(getString(R.string.update_no_asset))
            return
        }
        // 先确认已允许"安装未知应用"，否则下载完也装不上，白费流量
        if (!ApkInstaller.canInstall(this)) {
            toast(getString(R.string.update_need_permission))
            ApkInstaller.openInstallPermissionSettings(this)
            return
        }

        val fileName = release.apkName.ifBlank { "dsh-bridge-app.apk" }
        val dest = File(getExternalFilesDir(null), "updates/$fileName")

        binding.updateProgress.visibility = View.VISIBLE
        binding.updateProgress.progress = 0
        binding.btnUpdate.isEnabled = false

        downloadJob = lifecycleScope.launch {
            try {
                UpdateChecker.download(release.apkUrl, dest, release.apkSize) { percent ->
                    if (percent >= 0) {
                        binding.updateProgress.progress = percent
                        binding.btnUpdate.setText(getString(R.string.update_downloading, percent))
                    } else {
                        binding.btnUpdate.setText(R.string.update_checking)
                    }
                }
                binding.btnUpdate.setText(R.string.update_verifying)
                ApkInstaller.install(this@MainActivity, dest)
                binding.btnUpdate.setText(R.string.update_check)
            } catch (t: Throwable) {
                val message = t.message.orEmpty()
                if (message == "size-mismatch") {
                    toast(getString(R.string.update_size_mismatch))
                } else {
                    toast(getString(R.string.update_download_failed, message))
                }
                binding.btnUpdate.setText(getString(R.string.update_available, release.version))
            } finally {
                binding.updateProgress.visibility = View.GONE
                binding.btnUpdate.isEnabled = true
            }
        }
    }

    // ---- 杂项 ----

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MENU_PASSWORD = 1
        const val MENU_CLEAR_PASSWORD = 2
        const val MENU_COPY = 3
        const val MENU_DELETE = 4

        /** 连通性结果缓存时长：30 秒内回到首页不重复探测 */
        const val PROBE_TTL_MS = 30_000L

        /** 启动时自动检查更新的最小间隔：避免频繁消耗共享出口的 GitHub API 配额 */
        const val AUTO_CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L

        const val PREFS_UPDATE = "update"
        const val KEY_LAST_AUTO_CHECK = "last_auto_check"
    }
}
