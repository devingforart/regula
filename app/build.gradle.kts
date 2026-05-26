plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Compatibility shim for older Android Studio/Kotlin import flows that still
// request this task on the app module during Gradle sync.
tasks.register("prepareKotlinBuildScriptModel")

android {
    namespace = "com.unum.regula"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.unum.regula"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = true
    }

    androidResources {
        noCompress += "Regula/faceSdkResource.dat"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.regula.btdevice:api:9.1.+@aar")
    implementation("com.regula.documentreader.core:fullauthrfid:9.4.+@aar")
    implementation("com.regula.documentreader:api:+@aar") {
        isTransitive = true
    }
}
