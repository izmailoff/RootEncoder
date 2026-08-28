plugins {
  alias(libs.plugins.android.application)
}

android {
  namespace = "com.pedro.streamer"
  compileSdk = 37

  defaultConfig {
    applicationId = "com.pedro.streamer"
    minSdk = 16
    targetSdk = 37
    // TVC fork: the version carries a "-tvcsrtN" tag suffix; the demo app's code only needs the
    // numeric core (2.8.0 → 280), so drop the suffix before parsing rather than fail the build.
    versionCode = project.version.toString().substringBefore("-").replace(".", "").toInt()
    versionName = project.version.toString()
    multiDexEnabled = true
  }
  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  buildFeatures {
    buildConfig = true
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
}

kotlin {
  jvmToolchain(17)
}

dependencies {
  implementation(project(":library"))
  implementation(project(":extra-sources"))
  implementation(libs.androidx.constraintlayout)
  implementation(libs.androidx.appcompat)
  implementation(libs.androidx.multidex)
}
