#!/bin/bash

set -e

ANDROID_SDK_DIR="/home/runner/workspace/android-sdk"
CMDLINE_TOOLS_VERSION="11076708"

echo "Setting up Android SDK..."

mkdir -p "$ANDROID_SDK_DIR/cmdline-tools"

if [ ! -d "$ANDROID_SDK_DIR/cmdline-tools/latest" ]; then
    echo "Downloading Android SDK command line tools..."
    curl -sL "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_VERSION}_latest.zip" -o /tmp/cmdline-tools.zip
    
    echo "Extracting..."
    cd "$ANDROID_SDK_DIR/cmdline-tools"
    
    # Use node to extract since unzip is not available
    node -e "
    const unzipper = require('unzipper');
    const fs = require('fs');
    fs.createReadStream('/tmp/cmdline-tools.zip')
      .pipe(unzipper.Extract({ path: '.' }))
      .on('close', () => { console.log('Extracted'); });
    "
    
    mv cmdline-tools latest
    rm /tmp/cmdline-tools.zip
    
    chmod +x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
fi

export ANDROID_HOME="$ANDROID_SDK_DIR"
export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"

echo "Accepting SDK licenses..."
yes | sdkmanager --licenses > /dev/null 2>&1 || true

echo "Installing SDK components..."
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" "build-tools;33.0.1" > /dev/null 2>&1

echo "Creating local.properties..."
echo "sdk.dir=$ANDROID_SDK_DIR" > /home/runner/workspace/local.properties

echo "Android SDK setup complete!"
