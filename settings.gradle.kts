// 依赖仓库配置
//
// 顺序很重要：国内镜像放前面，官方源留在后面兜底。
// 阿里云镜像偶尔会缺个别产物（同步延迟），但有官方源垫底就不会卡住整个构建。
//
// 三个镜像仓的分工：
//   google         -> 对应 google()
//   gradle-plugin  -> 对应 gradlePluginPortal()
//   public         -> 聚合了 Maven Central，对应 mavenCentral()

pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")

        // ---- 以下为官方源兜底，镜像没有的产物会回落到这里 ----
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")

        // ---- 官方源兜底 ----
        google()
        mavenCentral()
    }
}

rootProject.name = "PocketNode"
include(":app")
