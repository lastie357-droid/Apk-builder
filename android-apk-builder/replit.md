# SampleApp - Android Project

## Overview
A basic Android application project built with Kotlin. The project uses the Gradle build system and targets Android API 34.

## Architecture
- **Build System**: Gradle 8.0 with Android Gradle Plugin 8.1.4
- **Language**: Kotlin 1.9.0
- **Target SDK**: Android 34 (Android 14)
- **Min SDK**: Android 24 (Android 7.0)

## Project Structure
- `app/` - The main Android application module
  - `src/main/java/com/example/sampleapp/` - Kotlin source files
  - `src/main/res/` - Android resources (layouts, values, drawables)
  - `src/main/AndroidManifest.xml` - App manifest
  - `build.gradle` - Module-level build configuration
- `gradle/wrapper/` - Gradle wrapper files
- `build.gradle` - Root-level build configuration
- `settings.gradle` - Project settings and module inclusion
- `android-sdk/` - Android SDK (installed via setup-android-sdk.sh)
- `setup-android-sdk.sh` - Script to download and install Android SDK components

## Dependencies
- androidx.core:core-ktx:1.12.0
- androidx.appcompat:appcompat:1.6.1
- com.google.android.material:material:1.11.0
- androidx.constraintlayout:constraintlayout:2.1.4

## Workflow
- **Build Android APK**: Runs `./gradlew assembleDebug` to build the debug APK
- Output APK is located at `app/build/outputs/apk/debug/app-debug.apk`

## Setup
The Android SDK is automatically installed via `npm install` which triggers `setup-android-sdk.sh`. The SDK is installed to `android-sdk/` in the project root.

## Environment Variables
- `ANDROID_HOME` must be set to `/home/runner/workspace/android-sdk` when running builds
