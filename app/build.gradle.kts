import java.io.FileInputStream
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.kapt)
}

// 优先级：
// 1. 仓库根目录 keystore.properties (本地开发)
// 2. 环境变量 KEYSTORE_BASE64 / KEYSTORE_PWD / KEY_ALIAS / KEY_PWD (CI Secrets)
// 3. fallback 到 debug 签名 (任何人都能本地试跑)
val releaseKeystoreProps = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) {
        load(FileInputStream(propsFile))
    }
}

// 说明：PKCS12 keystore 的密钥密码恒等于 store 密码，故 CI 不需要独立的 KEY_PWD。
val ciKeystoreBase64: String? = System.getenv("KEYSTORE_BASE64")
val ciKeystorePwd: String? = System.getenv("KEYSTORE_PWD")
val ciKeyAlias: String? = System.getenv("KEY_ALIAS")

val resolvedKeystoreFile: File? = when {
    releaseKeystoreProps.getProperty("storeFile") != null ->
        rootProject.file(releaseKeystoreProps.getProperty("storeFile"))
    !ciKeystoreBase64.isNullOrBlank() -> {
        val out = rootProject.layout.buildDirectory.file("ci-release.keystore").get().asFile
        out.parentFile.mkdirs()
        out.writeBytes(Base64.getDecoder().decode(ciKeystoreBase64))
        out
    }
    else -> null
}

android {
    namespace = "site.anzz.childkiosk"
    compileSdk = 34

    defaultConfig {
        applicationId = "site.anzz.childkiosk"
        minSdk = 28 // API 28 (Android 9.0 Pie) 是 setLockTaskFeatures 的推荐版本
        targetSdk = 34
        versionCode = 74
        versionName = "0.3.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (resolvedKeystoreFile != null) {
            create("release") {
                val resolvedStorePwd = releaseKeystoreProps.getProperty("storePassword") ?: ciKeystorePwd
                storeFile = resolvedKeystoreFile
                storePassword = resolvedStorePwd
                keyAlias = releaseKeystoreProps.getProperty("keyAlias") ?: ciKeyAlias
                // 本项目签名库为 PKCS12 格式，密钥密码必须与 store 密码一致。
                // 仅本地 keystore.properties 可显式覆盖 keyPassword；CI 一律回退到 store 密码，
                // 避免误配独立 KEY_PWD 导致 "Given final block not properly padded"。
                keyPassword = releaseKeystoreProps.getProperty("keyPassword") ?: resolvedStorePwd
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (resolvedKeystoreFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            isMinifyEnabled = false
        }
    }
    flavorDimensions += "distribution"
    productFlavors {
        create("standard") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"standard\"")
            buildConfigField("Boolean", "AMAP_LOCATION_SDK_INCLUDED", "false")
            buildConfigField("String", "AMAP_LOCATION_SDK_VERSION", "\"\"")
        }
        create("enhanced") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"enhanced\"")
            buildConfigField("Boolean", "AMAP_LOCATION_SDK_INCLUDED", "true")
            buildConfigField("String", "AMAP_LOCATION_SDK_VERSION", "\"11.2.000\"")
            proguardFiles("src/enhanced/proguard-rules.pro")
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
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8" // 与 Kotlin 1.9.22 配对的 Compose Compiler 版本
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    kapt(libs.androidx.room.compiler)

    // Coil Image Loader
    implementation(libs.coil.compose)

    add("enhancedImplementation", libs.amap.location)

    testImplementation(libs.junit)
}
