#!/bin/bash
set -e

ANDROID_SDK_DIR="/tmp/android-sdk"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
CMDLINE_TOOLS_ZIP="/tmp/cmdline-tools.zip"

echo "==> Setting up Android SDK..."

if [ ! -f "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" ]; then
    echo "  Downloading Android command-line tools..."
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP"

    echo "  Extracting..."
    mkdir -p /tmp/android-sdk-temp
    cd /tmp/android-sdk-temp
    jar xf "$CMDLINE_TOOLS_ZIP"
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"
    mv /tmp/android-sdk-temp/cmdline-tools "$ANDROID_SDK_DIR/cmdline-tools/latest"
    cd -

    chmod +x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
    echo "  Command-line tools ready."
else
    echo "  Command-line tools already present, skipping download."
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
# Use Zulu JDK 17 (HotSpot) — GraalVM's jlink does not support --disable-plugin system-modules
ZULU_JDK="/nix/store/0zjj9k6wz5hl4jizcfrkr0i4l8q45v51-zulu-ca-jdk-17.0.8.1"
if [ -d "$ZULU_JDK" ]; then
    export JAVA_HOME="$ZULU_JDK"
else
    export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
fi
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> Accepting SDK licenses..."
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /dev/null 2>&1 || true

echo "==> Installing SDK components (platforms;android-36, build-tools;35.0.0)..."
if [ ! -d "$ANDROID_SDK_DIR/platforms/android-36" ] || [ ! -d "$ANDROID_SDK_DIR/build-tools/35.0.0" ]; then
    sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0"
    echo "  SDK components installed."
else
    echo "  SDK components already installed, skipping."
fi

ROOT_DIR="$(pwd)"

echo "==> Writing local.properties..."
cat > local.properties <<EOF
sdk.dir=$ANDROID_SDK_DIR
EOF
cat > accessibility-apk/local.properties <<EOF
sdk.dir=$ANDROID_SDK_DIR
EOF

echo "==> Writing gradle.properties..."
cat > gradle.properties <<EOF
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.daemon=false
EOF
cat > accessibility-apk/gradle.properties <<EOF
android.useAndroidX=true
android.enableJetifier=true
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.daemon=false
EOF

echo "==> Downloading Gradle wrapper JAR (if missing)..."
if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    mkdir -p gradle/wrapper
    curl -fsSL "https://github.com/gradle/gradle/raw/v8.4.0/gradle/wrapper/gradle-wrapper.jar" \
        -o gradle/wrapper/gradle-wrapper.jar
    echo "  Gradle wrapper JAR downloaded."
else
    echo "  Gradle wrapper JAR already present."
fi

if [ ! -f "accessibility-apk/gradle/wrapper/gradle-wrapper.jar" ]; then
    cp gradle/wrapper/gradle-wrapper.jar accessibility-apk/gradle/wrapper/gradle-wrapper.jar
fi

chmod +x gradlew accessibility-apk/gradlew

mkdir -p apk-output

# ── Build: Single unified APK (main app + standalone accessibility process) ──
echo ""
echo "==> Building unified APK (RemoteAccess-debug.apk)..."
./gradlew assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk apk-output/RemoteAccess-debug.apk

echo ""
echo "==> Build complete! APK saved to: apk-output/RemoteAccess-debug.apk"
ls -lh apk-output/RemoteAccess-debug.apk
