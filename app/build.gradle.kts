import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---- release 签名 ----
// 密钥库与口令放在 keystore.properties（**不入库**，见 .gitignore）。
// 该文件缺失时 release 走未签名构建，别人 clone 下来依然能正常编译 debug/release。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}
val releaseStorePath = keystoreProps.getProperty("storeFile")
val hasReleaseSigning = keystorePropsFile.exists() &&
    releaseStorePath != null &&
    rootProject.file(releaseStorePath).exists()

android {
    namespace = "com.dshbridge.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dshbridge.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 6
        versionName = "1.1.2"
        resourceConfigurations += listOf("zh", "en")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(releaseStorePath!!)
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // minSdk 26 起 v2 签名已足够（v1/JAR 签名只对 API < 24 有必要），
                // 实测 apksigner 报告 v1=false、v2=true，属预期
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 资源裁剪与 R8 关闭：ZXing 等库有反射/资源名访问，保持与 debug 行为一致更稳。
            // 需要减小体积时再单独打开并回归测一遍扫码与 WebView。
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // 首页右下角要显示当前版本号（BuildConfig.VERSION_NAME），AGP 8 默认不生成 BuildConfig
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // 访问密码的加密存储（AES256-GCM + Android Keystore 主密钥）
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // 二维码扫描：Camera 实时解码用 zxing-android-embedded 的 DecoratedBarcodeView（可嵌自定义布局）；
    // 「从相册选择」的图片解码直接用 ZXing core，所以这里显式声明它（此前只是传递依赖，不该依赖这个巧合）
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")

    // 原生登录：POST /__dsh_bridge__/login 换取会话 cookie
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
