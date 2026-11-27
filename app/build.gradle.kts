plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sbiografia.qwenvoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sbiografia.qwenvoice"
        minSdk = 26
        targetSdk = 35
        versionCode = 10
        versionName = "1.5.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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
}

dependencies {
    // sherpa-onnx v2.0.1 공식 prebuilt AAR (jitPack/Maven Central 배포본이 없어 파일로 동봉)
    // 내부: classes.jar(Kotlin API) + jni/{arm64-v8a,armeabi-v7a,x86,x86_64}/libsherpa-onnx-jni.so, libonnxruntime.so
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 후처리 단계(문장부호·줄바꿈·입력 완료 등)는 안드로이드에 기대지 않는 순수 Kotlin 이라
    // PC에서 `gradlew test` 로 그대로 확인한다.
    testImplementation("junit:junit:4.13.2")
}
