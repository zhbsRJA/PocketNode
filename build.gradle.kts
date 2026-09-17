plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.zhbsrja.pocketnode"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "io.github.zhbsrja.pocketnode"
        minSdk = 28
        targetSdk = 37
        // versionCode 必须是递增的正整数（Android 靠它判断能不能覆盖安装），
        // 不能直接用 "26.9.17.1" 这种字符串。
        // 这里把版本号的数字直接拼成一个整数：26.9.17.1 → 26091701
        // 好处是升级时改 versionName 顺手改这个，两边一眼能对上。
        versionCode = 26091701
        versionName = "26.9.17.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ─────────────────────────────────────────────────────────────
    // 发布签名
    //
    // 密钥放在项目目录之外（D:\AndroidProjects\keystores），这样不管
    // .gitignore 写没写，都不可能被误提交进版本库。
    //
    // ⚠️ 密码是明文写在这里的。个人项目可以接受，但如果哪天要上架或
    // 多人协作，应该改成从 local.properties 或环境变量读取，例如：
    //   storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
    //
    // ⚠️ 这个密钥丢了就再也无法给已安装的应用发更新（Android 用签名
    // 来校验升级包的身份）。请单独备份。
    // ─────────────────────────────────────────────────────────────
    signingConfigs {
        create("release") {
            storeFile = file("D:/AndroidProjects/keystores/pocketnode-release.jks")
            storePassword = "pocketnode2026"
            keyAlias = "pocketnode"
            keyPassword = "pocketnode2026"
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")

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
