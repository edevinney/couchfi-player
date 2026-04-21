import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun p(key: String, default: String = ""): String = localProps.getProperty(key, default)

android {
    namespace = "com.couchfi.player"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.couchfi.player"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"

        buildConfigField("String", "SMB_HOST",     "\"${p("smb.host")}\"")
        buildConfigField("String", "SMB_USER",     "\"${p("smb.user")}\"")
        buildConfigField("String", "SMB_PASSWORD", "\"${p("smb.password")}\"")
        buildConfigField("String", "SMB_SHARE",    "\"${p("smb.share")}\"")
        buildConfigField("String", "SMB_TESTFILE", "\"${p("smb.testfile")}\"")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.leanback:leanback:1.2.0-alpha04")
    implementation("com.hierynomus:smbj:0.13.0")
    implementation("org.yaml:snakeyaml:2.2")
}
