package com.dshbridge.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.dshbridge.app.databinding.ActivityScannerBinding
import com.dshbridge.app.ui.Insets
import com.dshbridge.app.util.GalleryQrDecoder
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 扫码页：相机实时扫码 + **从相册选图识别**，两条路都把结果回传给首页。
 *
 * 实现说明：
 *  - 相机部分用 zxing-android-embedded 的 [com.journeyapps.barcodescanner.DecoratedBarcodeView]
 *    （可直接嵌进自定义布局），而不是库自带的 CaptureActivity：后者界面固定、无法加入
 *    "从相册选择"入口，也无法换掉那套偏旧的取景框。
 *  - 库自带的取景框没有任何可定制属性（外观是 protected 硬编码），所以隐藏它，
 *    改用自绘的 ScanOverlayView。
 *  - 相册图片的解码走 ZXing core（见 GalleryQrDecoder）：相机实时解码与图片解码是两套路径。
 *  - 相机权限必须自己申请：用 DecoratedBarcodeView 时不再有库的 CaptureActivity 代劳。
 */
class ScanActivity : AppCompatActivity() {

    private lateinit var binding: ActivityScannerBinding

    /** 防止连续回调重复返回（相机回调可能短时间内触发多次） */
    private var handled = false
    private var torchOn = false
    private var cameraGranted = false

    /** Android 13+ 的系统相册选择器；在老版本上自动回退到兼容实现，都不需要存储权限 */
    private val pickImage = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            binding.barcodeView.resume()
        } else {
            decodeFromGallery(uri)
        }
    }

    private val requestCamera = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (granted) {
            binding.barcodeView.resume()
        } else {
            toast(getString(R.string.scanner_camera_denied))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 预览铺满全屏（含系统栏下方），只让顶部/底部控件避开系统栏
        Insets.applyTopInsetPadding(binding.topBar)
        Insets.applyBottomInsetPadding(binding.bottomBar)

        // 隐藏库自带取景框与状态文字，外观交给自绘 overlay
        binding.barcodeView.viewFinder.visibility = View.GONE
        binding.barcodeView.setStatusText("")

        binding.barcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult) {
                val text = result.text
                if (!text.isNullOrBlank()) finishWithResult(text)
            }
        })

        binding.btnBack.setOnClickListener { finish() }
        binding.btnTorch.setOnClickListener { toggleTorch() }
        binding.btnGallery.setOnClickListener { pickFromGallery() }

        cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!cameraGranted) {
            // 相机统一在 onResume 或授权回调里启动：onCreate 阶段视图尚未完成布局，
            // 这里再 resume 一次属于重复启动
            requestCamera.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        handled = false
        if (cameraGranted) {
            binding.barcodeView.resume()
            // pause/resume 会重置手电筒状态，这里按用户选择恢复
            if (torchOn) binding.barcodeView.setTorchOn()
        }
    }

    override fun onPause() {
        binding.barcodeView.pause()
        super.onPause()
    }

    // ---- 手电筒 ----

    private fun toggleTorch() {
        torchOn = !torchOn
        if (torchOn) binding.barcodeView.setTorchOn() else binding.barcodeView.setTorchOff()
        binding.btnTorch.setText(
            if (torchOn) R.string.scanner_torch_off else R.string.scanner_torch_on
        )
    }

    // ---- 从相册选择 ----

    private fun pickFromGallery() {
        // 选图期间先关相机，避免与相册抢摄像头
        binding.barcodeView.pause()
        pickImage.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    private fun decodeFromGallery(uri: Uri) {
        binding.galleryProgress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val text = withContext(Dispatchers.IO) {
                GalleryQrDecoder.decode(this@ScanActivity, uri)
            }
            binding.galleryProgress.visibility = View.GONE
            if (text.isNullOrBlank()) {
                toast(getString(R.string.scanner_gallery_failed))
                binding.barcodeView.resume()
            } else {
                finishWithResult(text)
            }
        }
    }

    // ---- 返回结果 ----

    private fun finishWithResult(text: String) {
        if (handled) return
        handled = true
        setResult(RESULT_OK, Intent().putExtra(EXTRA_SCAN_RESULT, text))
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val EXTRA_SCAN_RESULT = "scan_result"

        fun intent(context: Context): Intent = Intent(context, ScanActivity::class.java)
    }
}
