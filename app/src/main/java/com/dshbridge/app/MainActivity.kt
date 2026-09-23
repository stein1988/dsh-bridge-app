package com.dshbridge.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import com.dshbridge.app.net.BridgeAuth
import com.dshbridge.app.net.WebViewCookies
import com.dshbridge.app.ui.Insets
import com.dshbridge.app.ui.LinkAdapter
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * 首页：链接记录列表 + 扫码/手动添加。
 *
 * 每条记录支持「记住密码 / 修改密码」「清除密码」：密码通过 [BridgeAuth] 原生登录
 * 校验通过后存进 [CredentialVault]（Keystore 加密），打开会话页时自动换取会话 cookie。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var store: LinkStore
    private lateinit var vault: CredentialVault
    private lateinit var adapter: LinkAdapter

    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) handleScanned(contents)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Insets.applySystemBarsPadding(binding.root)
        setSupportActionBar(binding.toolbar)

        store = LinkStore(this)
        vault = CredentialVault(this)

        adapter = LinkAdapter(
            onClick = { openLink(it) },
            onMenu = { anchor, record -> showItemMenu(anchor, record) }
        )
        binding.recycler.layoutManager = LinearLayoutManager(this)
        binding.recycler.adapter = adapter
        binding.fabAdd.setOnClickListener { showAddDialog() }

        if (!vault.encrypted) {
            Toast.makeText(this, R.string.vault_plain_warning, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // ---- 列表 ----

    private fun refresh() {
        val items = store.all()
        val withPassword = items.filter { vault.hasPassword(it.id) }.map { it.id }.toSet()
        adapter.submit(items, withPassword)

        val empty = items.isEmpty()
        binding.emptyState.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    // ---- 添加 ----

    private fun showAddDialog() {
        val choices = arrayOf(getString(R.string.menu_scan), getString(R.string.menu_manual))
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.fab_add)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> launchScan()
                    1 -> showManualDialog()
                }
            }
            .show()
    }

    private fun launchScan() {
        scanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                // 用自家 ScannerActivity：库默认的扫码页是横屏锁定，这里改为跟随手机方向
                .setCaptureActivity(ScannerActivity::class.java)
                .setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false)
        )
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
                refresh()
                dialog.dismiss()
                openLink(record)
            }
        }
        dialog.show()
    }

    /**
     * 处理扫码结果。
     *
     * dsh-bridge 的局域网二维码形如 `http://192.168.1.10:3082/?auth=<secretToken>`：
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
        refresh()
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

    // ---- 单项操作菜单 ----

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
                refresh()
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
                toast(getString(R.string.link_deleted))
                refresh()
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
     * 先在服务端验证密码，再决定是否保存。
     *
     * 三种结果区别对待：
     *  - 成功：保存，并顺手把会话 cookie 写进 WebView，随后打开即为已登录；
     *  - 401：**不保存**，明确告知密码错；
     *  - 网络失败：不能据此判定密码错，允许保存但如实提示"未能联网验证"。
     */
    private fun verifyAndSave(
        record: LinkRecord,
        password: String,
        dialog: AlertDialog,
        dialogBinding: DialogPasswordBinding
    ) {
        lifecycleScope.launch {
            when (val result = BridgeAuth.login(record.url, password)) {
                is BridgeAuth.Result.Ok -> {
                    vault.savePassword(record.id, password)
                    WebViewCookies.applySession(record.url, result.sessionToken)
                    toast(getString(R.string.password_saved_ok))
                    dialog.dismiss()
                    refresh()
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
                    refresh()
                }
            }
        }
    }

    private fun showDialogError(dialogBinding: DialogPasswordBinding, message: String) {
        dialogBinding.tvError.text = message
        dialogBinding.tvError.visibility = View.VISIBLE
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val MENU_PASSWORD = 1
        const val MENU_CLEAR_PASSWORD = 2
        const val MENU_COPY = 3
        const val MENU_DELETE = 4
    }
}
