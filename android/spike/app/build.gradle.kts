plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.chaquopy)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.flovera.app"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.flovera.app"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        ndk {
          abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    flavorDimensions += "installSlot"
    productFlavors {
        create("flovera") {
            dimension = "installSlot"
            applicationId = "com.flovera.app"
        }
        create("design") {
            dimension = "installSlot"
            applicationId = "com.flovera.design"
        }
        create("style") {
            dimension = "installSlot"
            applicationId = "com.flovera.style"
        }
        create("legacy") {
            dimension = "installSlot"
            applicationId = "com.example.ailinuxvmspike"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    packaging {
      jniLibs {
        useLegacyPackaging = true
      }
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
        excludes += "META-INF/DEPENDENCIES"
        excludes += "META-INF/INDEX.LIST"
        excludes += "META-INF/LICENSE.md"
        excludes += "META-INF/NOTICE.md"
        excludes += "META-INF/io.netty.versions.properties"
      }
    }
}

chaquopy {
  defaultConfig {
    version = "3.11"
    pip {
      install("lxml==5.3.0")
      install("python-docx==1.2.0")
      install("openpyxl==3.1.5")
      install("XlsxWriter==3.2.9")
      install("pypdf==6.11.0")
      install("Markdown==3.10.2")
      install("Jinja2==3.1.6")
    }
  }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.koog.agents)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.jtokkit)
  implementation(libs.markwon.core)
  implementation(libs.markwon.ext.tables)
  implementation(libs.jgit)
  implementation(libs.mlkit.text.recognition)
  implementation(libs.mlkit.text.recognition.chinese)
  runtimeOnly(libs.poi.ooxml)
  runtimeOnly(libs.stax.api)
  runtimeOnly(libs.woodstox.core)
  implementation("org.codehaus.groovy:groovy:2.4.21:grooid")
  implementation("com.android.tools:r8:9.1.31")

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)
}
