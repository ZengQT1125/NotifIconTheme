plugins {
    id("com.android.application")
}

android {
    namespace = "com.littletater.notificontheme"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.littletater.notificontheme"
        minSdk = 29
        targetSdk = 36
        versionCode = 21
        versionName = "3.11"
    }

    // 签名固定：换签名 = 必须卸载重装，模块配置也一起丢。
    // CI 用 secrets 里的 keystore（base64），本地直接吃仓库里的 keystore/spud.jks。
    signingConfigs {
        create("spud") {
            storeFile = file(
                System.getenv("SPUD_KEYSTORE")
                    ?: rootProject.file("keystore/spud.jks").absolutePath
            )
            storePassword = System.getenv("SPUD_STORE_PASS") ?: "android"
            keyAlias = System.getenv("SPUD_KEY_ALIAS") ?: "spud"
            keyPassword = System.getenv("SPUD_KEY_PASS") ?: "android"
        }
    }

    // Xposed 靠类名/方法名反射进 SystemUI，混淆会把入口干掉，永远不开 minify。
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("spud")
        }
        debug {
            signingConfig = signingConfigs.getByName("spud")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/*.version")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // Xposed API 82（de.robv.android.xposed），只编译期用，不打进 APK。
    compileOnly(files("libs/api-82.jar"))
}
