import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.zhbsrja.pocketnode"
    compileSdk {
        version = release(37)
    }

    // ══════════════════════════════════════════════════════════════
    // 分发渠道
    //
    // sideload —— 自己签名安装的完整版。保留了全部能力：
    //             全盘存储权限（模型放 /storage/emulated/0/PNAI）
    //             无障碍服务（智能体控制设备）
    //
    // play     —— 上架 Google Play 的版本。**必须砍掉上面两项**：
    //             · MANAGE_EXTERNAL_STORAGE 只允许文件管理器/杀毒/备份类
    //               应用使用，其他一律驳回
    //             · 无障碍服务用于自动化操作属于灰色地带，过审风险高
    //
    // 砍掉的方式见 app/src/play/AndroidManifest.xml 里的 tools:node="remove"，
    // 代码里的能力降级见 BuildCapabilities.kt。
    //
    // ⚠️ 两个 flavor 的 applicationId 必须不同，否则没法装在同一台手机上
    //    做对比测试（签名也不同）。
    // ══════════════════════════════════════════════════════════════
    flavorDimensions += "dist"
    productFlavors {
        create("sideload") {
            dimension = "dist"
            applicationId = "io.github.zhbsrja.pocketnode"
            versionNameSuffix = ""
        }
        create("play") {
            dimension = "dist"
            // 加 .play 后缀：两个版本能同时装，方便对比；
            // 而且用户反馈问题时能一眼看出是哪个渠道的
            applicationId = "io.github.zhbsrja.pocketnode.play"
            versionNameSuffix = ""
            // Play 版不能带这些符号链接之外的资源，保持干净
        }
    }

    defaultConfig {
        applicationId = "io.github.zhbsrja.pocketnode"
        minSdk = 28
        targetSdk = 37
        // versionCode 必须是递增的正整数（Android 靠它判断能不能覆盖安装），
        // 不能直接用 "26.9.19" 这种字符串。
        //
        // 映射规则：版本号数字直接拼成整数，不足 8 位补 0。
        //   26.9.17.1   →  26091701
        //   26.9.19     →  26091900
        //   26.9.19.2   →  26091902
        //
        // ⚠️ 必须保证递增。改成更小的值会导致无法覆盖安装，
        //    只能先卸载再装，用户数据会丢。
        versionCode = 26091902
        versionName = "26.9.19.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ─────────────────────────────────────────────────────────────
    // 发布签名
    //
    // 口令不写进仓库：从 local.properties（已被 .gitignore 排除）或同名
    // 环境变量读取。没配置时不创建 release 签名配置，release 构建产出
    // 未签名包 —— 这样 clone 下来的人不需要任何密钥就能跑通构建。
    //
    // local.properties 里要写：
    //   RELEASE_STORE_FILE=D:/path/to/pocketnode-release.jks
    //   RELEASE_STORE_PASSWORD=xxxx
    //   RELEASE_KEY_ALIAS=pocketnode
    //   RELEASE_KEY_PASSWORD=xxxx
    //
    // ⚠️ 这个密钥丢了就再也无法给已安装的应用发更新（Android 用签名
    // 来校验升级包的身份）。请单独备份，并且不要提交到任何仓库。
    // ─────────────────────────────────────────────────────────────
    signingConfigs {
        val releaseProps = Properties()
        rootProject.file("local.properties")
            .takeIf { it.exists() }
            ?.inputStream()
            ?.use { releaseProps.load(it) }

        fun releaseProp(key: String): String? =
            releaseProps.getProperty(key) ?: System.getenv(key)

        val storePath = releaseProp("RELEASE_STORE_FILE")
        val storePasswordValue = releaseProp("RELEASE_STORE_PASSWORD")
        if (!storePath.isNullOrBlank() && !storePasswordValue.isNullOrBlank()) {
            create("release") {
                storeFile = file(storePath)
                storePassword = storePasswordValue
                keyAlias = releaseProp("RELEASE_KEY_ALIAS") ?: "pocketnode"
                keyPassword = releaseProp("RELEASE_KEY_PASSWORD") ?: storePasswordValue
            }
        }
    }

    buildTypes {
        release {
            // 配了签名就用 release 签名；没配则保持未签名，不阻断构建
            val releaseSigning = signingConfigs.findByName("release")
            if (releaseSigning != null) {
                signingConfig = releaseSigning
            }

            // 开启 R8：删无用代码 + 混淆 + 资源压缩。
            // 这个项目 Debug 包 85MB，其中绝大部分是 MediaPipe 的原生库
            // 和未使用的 androidx 代码，R8 能砍掉不少。
            //
            // 代价是必须给用反射/native 的库写 keep 规则 —— 见
            // proguard-rules.pro。MediaPipe 大量依赖 JNI，混淆了类名
            // native 层就找不到方法了。
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            optimization {
                enable = true
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // AGP 8 起默认关闭。BuildCapabilities 要读 BuildConfig.FLAVOR 来区分渠道，
        // 不开的话 BuildConfig 类根本不会生成。
        buildConfig = true
    }

    // ─────────────────────────────────────────────────────────────
    // 不拆分 ABI，只出一个合并包
    //
    // 之前试过 ABI 分包（手机装 arm64 那份只有 15.5 MB，比合并包小 71%），
    // 但那样会产出三个 APK，发布时要挑、要解释，反而麻烦。
    //
    // 现在的取舍：**一个包，54 MB，什么设备都能装。**
    //   arm64-v8a     12.3 MB   手机
    //   armeabi-v7a    7.9 MB   32 位老设备
    //   x86           16.4 MB   模拟器
    //   x86_64        14.4 MB   模拟器
    //
    // 如果哪天嫌大，把下面这段 splits 打开就能按架构拆开，
    // 产物会变成三份（arm64 / x86_64 / universal）。
    // ─────────────────────────────────────────────────────────────
    // splits {
    //     abi {
    //         isEnable = true
    //         reset()
    //         include("arm64-v8a", "x86_64")
    //         isUniversalApk = true
    //     }
    // }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.mediapipe.tasks.genai)
    implementation(libs.nanohttpd)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
