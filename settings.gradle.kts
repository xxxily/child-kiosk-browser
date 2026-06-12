// 仓库源按环境排序：
// - CI（GitHub Actions 自动设置 CI=true，runner 在海外）优先用官方 google()/mavenCentral()，
//   稳定且快；阿里云镜像仅作兜底。
// - 本地开发优先用阿里云镜像加速国内拉取，官方源兜底。
// Gradle 对 5xx（如阿里云镜像偶发 502 Bad Gateway）不会自动回退到下一个源，
// 故必须让 CI 把可靠的官方源放在最前，避免镜像抖动拖垮整条构建。
//
// 注意：pluginManagement / dependencyResolutionManagement 块会被 Gradle 提前隔离求值，
// 顶层 val 不在其作用域内，故 CI 判断必须在每个块内联读取。

pluginManagement {
    repositories {
        if (System.getenv("CI").equals("true", ignoreCase = true)) {
            google()
            mavenCentral()
            gradlePluginPortal()
            maven { url = java.net.URI("https://maven.aliyun.com/repository/google") }
            maven { url = java.net.URI("https://maven.aliyun.com/repository/public") }
            maven { url = java.net.URI("https://maven.aliyun.com/repository/gradle-plugin") }
        } else {
            maven { url = java.net.URI("https://maven.aliyun.com/repository/google") }
            maven { url = java.net.URI("https://maven.aliyun.com/repository/public") }
            maven { url = java.net.URI("https://maven.aliyun.com/repository/gradle-plugin") }
            google()
            mavenCentral()
            gradlePluginPortal()
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("CI").equals("true", ignoreCase = true)) {
            google()
            mavenCentral()
            maven { url = java.net.URI("https://maven.aliyun.com/repository/google") }
            maven { url = java.net.URI("https://maven.aliyun.com/repository/public") }
        } else {
            maven { url = java.net.URI("https://maven.aliyun.com/repository/google") }
            maven { url = java.net.URI("https://maven.aliyun.com/repository/public") }
            google()
            mavenCentral()
        }
    }
}

rootProject.name = "child-kiosk-browser"
include(":app")
