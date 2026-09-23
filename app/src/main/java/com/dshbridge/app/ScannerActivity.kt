package com.dshbridge.app

import com.journeyapps.barcodescanner.CaptureActivity

/**
 * 扫码界面。
 *
 * 必须自己声明一个子类：ZXing 自带的 `CaptureActivity` 在库清单里被写死为
 * `android:screenOrientation="sensorLandscape"`，手机竖着拿会被强制转成横屏，
 * 扫码体验很别扭。这里在清单里改成 `fullSensor`，扫描界面跟随手机方向。
 */
class ScannerActivity : CaptureActivity()
