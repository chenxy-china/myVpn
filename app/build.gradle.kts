plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.testvpn"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.testvpn"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

}

dependencies {

    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
//    implementation(files("libs\\framework-connectivity.impl.jar"))
//    debugImplementation(files("libs\\framework-connectivity.jar"))
    implementation (files("libs\\modules-utils-build.jar"))
//    implementation(files("libs\\layoutlib.jar"))
//    compileOnly (files("libs\\NetworkStackApi29Shims.jar"))
//    compileOnly (files("libs\\NetworkStackApi31Shims.jar"))
//    compileOnly (files("libs\\NetworkStackApi33Shims.jar"))
//    compileOnly (files("libs\\NetworkStackApi34Shims.jar"))
//    compileOnly (files("libs\\NetworkStackShimsCommon.jar"))
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}