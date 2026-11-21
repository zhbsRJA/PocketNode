# PocketNode

在你的 Android 9.0 及以上设备上本地部署 AI 模型，可以自行导入。

支持 Español、বাংলা、Português、Русский、Français、Bahasa Indonesia / Bahasa Melayu、Deutsch、日本語、한국어、العربية、हिन्दी、اردو 等 13 种语言。

---

## 这是什么

在手机上跑量化语言模型，然后把手机变成一个局域网可访问的 OpenAI 兼容 API 服务。

- **本地推理** —— MediaPipe LLM Inference，模型下载一次，加载到内存后完全离线，飞行模式也能用
- **局域网 API** —— 内置 HTTP 服务，提供 `/v1/chat/completions`、`/v1/models`，支持 SSE 流式响应
- **自带模型** —— 可以直接导入已有的 `.task` 模型文件，自己命名
- 没有云端、没有账号、没有遥测

## 下载

从 [Releases](https://github.com/zhbsRJA/PocketNode/releases) 下载 sideload 版 APK 直接安装。

sideload 版保留了完整能力：全盘存储权限（模型放 `/storage/emulated/0/PNAI`）、无障碍服务（智能体控制设备）。

Play 版必须砍掉这两项（MANAGE_EXTERNAL_STORAGE 只允许文件管理器类应用使用，无障碍自动化过审风险高），`applicationId` 多一个 `.play` 后缀，所以两个版本可以同时装在一台手机上对比。

## 构建

### 环境要求

| 项目 | 版本 |
| --- | --- |
| JDK | 25（Android Studio 2026 自带的 JBR 就是完整 OpenJDK 25） |
| Android SDK | Platform 37、Build-Tools 36 |
| Gradle | 9.6（仓库自带 wrapper） |
| Android Gradle Plugin | 9.4.0 |
| Kotlin | 2.2.10 |

工具链版本固定在 `gradle/gradle-daemon-jvm.properties`（JDK 25）和 `gradle/wrapper/gradle-wrapper.properties`（Gradle 9.6）。

### 命令

```bash
# Debug 包，不需要任何签名配置
./gradlew :app:assembleSideloadDebug

# Release 包，需要本地签名配置（见下一节）
./gradlew :app:assembleSideloadRelease
./gradlew :app:bundlePlayRelease

# 装到已连接设备
./gradlew :app:installSideloadDebug
```

产物在 `app/build/outputs/apk/` 和 `app/build/outputs/bundle/`。

### 签名

仓库里没有任何签名口令。`app/build.gradle.kts` 从 `local.properties`（已在 `.gitignore` 里排除）或同名环境变量读取：

```properties
RELEASE_STORE_FILE=D:/path/to/pocketnode-release.jks
RELEASE_STORE_PASSWORD=xxxx
RELEASE_KEY_ALIAS=pocketnode
RELEASE_KEY_PASSWORD=xxxx
```

没配置时不会创建 release 签名配置，release 构建产出未签名包，`assembleDebug` 完全不受影响。

```bash
./gradlew :app:signingReport   # 确认当前签名配置
```

### 本机 JDK 路径

如果 JDK 25 不在默认位置，写在**用户级** `~/.gradle/gradle.properties`，不要写进仓库：

```properties
org.gradle.java.installations.paths=D:/Android/Studio/jbr
```

`android.builder.sdkDownload=false` 已经打开：缺 SDK 组件时会立刻报明确错误，而不是静默挂起几十分钟。

## 目录结构

```
app/
  src/main/                      sideload 版主代码与资源
    java/io/github/zhbsrja/pocketnode/
      agent/                     无障碍服务与设备控制
      data/                      模型目录、会话、设置、下载
      inference/                 MediaPipe LLM 推理封装
      server/                    OpenAI 兼容 HTTP 服务
      ui/                        Compose 界面
    res/values-*/                多语言字符串
  src/play/                      Play 版清单，用 tools:node 移除受限权限
gradle/libs.versions.toml        依赖版本目录
legal/                           隐私政策与站点说明
tools/                           模型下载与本地服务脚本
```

## 模型

`.task` 格式（MediaPipe LLM Inference）。`tools/download-models.ps1` 可以从 hf-mirror 拉取常用模型：

```powershell
powershell -ExecutionPolicy Bypass -File tools/download-models.ps1

# 自定义输出目录 + 走官方源
powershell -ExecutionPolicy Bypass -File tools/download-models.ps1 D:\MyModels official
```

也可以直接导入任意 `.task` 模型文件。

模型大小在 0.5 GB 到 3.7 GB 之间，建议用 WiFi 下载。推理跑在 CPU 上，大模型需要旗舰机。

## 隐私

所有推理都在设备上完成，不收集、不上传任何数据。详见 [privacy.md](privacy.md)。

## 许可证

见 [LICENSE](LICENSE)。
