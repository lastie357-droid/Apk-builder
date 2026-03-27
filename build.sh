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
export JAVA_HOME=$(dirname $(dirname $(readlink -f $(which java))))
export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

echo "==> Accepting SDK licenses..."
yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses > /dev/null 2>&1 || true

echo "==> Installing SDK components (platforms;android-34, build-tools;34.0.0)..."
if [ ! -d "$ANDROID_SDK_DIR/platforms/android-34" ] || [ ! -d "$ANDROID_SDK_DIR/build-tools/34.0.0" ]; then
    sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-34" "build-tools;34.0.0"
    echo "  SDK components installed."
else
    echo "  SDK components already installed, skipping."
fi

echo "==> Writing local.properties..."
cat > local.properties <<EOF
sdk.dir=$ANDROID_SDK_DIR
EOF

echo "==> Writing gradle.properties..."
cat > gradle.properties <<EOF
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

chmod +x gradlew

echo "==> Building debug APK..."
./gradlew assembleDebug --no-daemon

mkdir -p apk-output
cp app/build/outputs/apk/debug/app-debug.apk apk-output/RemoteAccess-debug.apk

echo ""
echo "==> Build complete! APK saved to: apk-output/RemoteAccess-debug.apk"
ls -lh apk-output/RemoteAccess-debug.apk
