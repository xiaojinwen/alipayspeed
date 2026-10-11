import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名：复用项目统一的 keystore.properties（已被 .gitignore 忽略）
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "xiaojw.alipayspeed"
    // 本机SDK 只有 android-34 / android-36，取 36。
    // compileSdk 只影响编译期 API 可用性，运行时不依赖。
    compileSdk = 36

    defaultConfig {
        applicationId = "xiaojw.alipayspeed"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (keystoreProps["storeFile"] != null) {
            create("release") {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProps["storeFile"] != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            if (keystoreProps["storeFile"] != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    applicationVariants.all {
        outputs.all {
            val suffix = if (name.equals("debug", ignoreCase = true)) "-debug" else ""
            (this as? com.android.build.gradle.internal.api.BaseVariantOutputImpl)
                ?.outputFileName = "alipay-speed_${defaultConfig.versionName}${suffix}.apk"
        }
    }

    buildFeatures {
        compose = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // LSPosed / Xposed API：仅编译期依赖，运行时由框架提供。
    // 本模块刻意不引入 androidx —— 纯 Xposed API 已足够，
    // 少一个依赖就少一个版本冲突与离线构建失败点。
    compileOnly("de.robv.android.xposed:api:82")
}
