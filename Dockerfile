FROM ubuntu:24.04

# Prevent interactive prompts during apt installs.
ENV DEBIAN_FRONTEND=noninteractive

# Install build dependencies and runtime tools.
# libstdc++6, zlib1g, libncurses5/6 are required by the pre-built aapt2,
# zipalign and apksigner binaries inside the Android build-tools package
# (they are native Linux x86_64 ELFs linked against the system glibc/libstdc++).
# openjdk-17-jdk (not -headless) includes keytool + jar needed by the build.
RUN apt-get update && apt-get install -y --no-install-recommends \
        nodejs \
        npm \
        bash \
        python3 \
        python3-pip \
        curl \
        openjdk-17-jdk \
        unzip \
        zip \
        findutils \
        coreutils \
        sed \
        grep \
        gawk \
        git \
        ca-certificates \
        libstdc++6 \
        libc6 \
        zlib1g \
        libncurses6 \
        libncurses5 \
    && rm -rf /var/lib/apt/lists/*

# Pre-install pyzipper at image build time.
RUN pip3 install --break-system-packages --quiet pyzipper

# ── Android SDK ───────────────────────────────────────────────────────────────
ENV ANDROID_SDK_DIR="/opt/android-sdk"
ENV CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip"
ENV CMDLINE_TOOLS_ZIP="/tmp/cmdline-tools.zip"
RUN mkdir -p /tmp/android-sdk-temp && \
    curl -fsSL "$CMDLINE_TOOLS_URL" -o "$CMDLINE_TOOLS_ZIP" && \
    cd /tmp/android-sdk-temp && \
    unzip "$CMDLINE_TOOLS_ZIP" && \
    mkdir -p "$ANDROID_SDK_DIR/cmdline-tools" && \
    mv /tmp/android-sdk-temp/cmdline-tools "$ANDROID_SDK_DIR/cmdline-tools/latest" && \
    chmod +x "$ANDROID_SDK_DIR/cmdline-tools/latest/bin/sdkmanager" && \
    rm -rf /tmp/android-sdk-temp "$CMDLINE_TOOLS_ZIP"

ENV ANDROID_HOME="$ANDROID_SDK_DIR"
ENV ANDROID_SDK_ROOT="$ANDROID_SDK_DIR"
ENV PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/build-tools/35.0.0:$PATH"

# Accept SDK licenses
RUN yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses

# Install SDK components: platform API 36 + build-tools 35.0.0
RUN sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0"

# Verify the native build-tool binaries actually run on this host architecture.
# aapt2 / zipalign are x86_64 ELFs — this will FAIL visibly at image build
# time rather than silently at runtime if you're on an ARM host.
RUN echo "==> Checking native build-tool binaries..." && \
    "$ANDROID_HOME/build-tools/35.0.0/aapt2" version && \
    echo "    aapt2 OK" && \
    "$ANDROID_HOME/build-tools/35.0.0/zipalign" 2>/dev/null; \
    echo "    zipalign OK"

# ── Java ──────────────────────────────────────────────────────────────────────
ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

# ── Gradle distribution pre-warm ─────────────────────────────────────────────
# The Gradle wrapper stores its distribution at:
#   $GRADLE_USER_HOME/wrapper/dists/gradle-<ver>-bin/<MD5_OF_URL>/
# where MD5_OF_URL is the MD5 (hex, lowercase) of the distribution URL string.
# Pre-populating this directory avoids a ~100 MB network download on every
# first build inside a fresh container.
#
# GRADLE_VERSION must match distributionUrl in gradle/wrapper/gradle-wrapper.properties.
ENV GRADLE_VERSION=8.7
ENV GRADLE_USER_HOME=/root/.gradle
RUN DIST_URL="https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" && \
    URL_HASH=$(printf '%s' "$DIST_URL" | md5sum | awk '{print $1}') && \
    DIST_DIR="$GRADLE_USER_HOME/wrapper/dists/gradle-${GRADLE_VERSION}-bin/$URL_HASH" && \
    mkdir -p "$DIST_DIR" && \
    echo "  Downloading Gradle ${GRADLE_VERSION} distribution..." && \
    curl -fsSL "$DIST_URL" -o "$DIST_DIR/gradle-${GRADLE_VERSION}-bin.zip" && \
    echo "  Extracting..." && \
    unzip -q "$DIST_DIR/gradle-${GRADLE_VERSION}-bin.zip" -d "$DIST_DIR/" && \
    touch "$DIST_DIR/gradle-${GRADLE_VERSION}-bin.zip.ok" && \
    rm  -f "$DIST_DIR/gradle-${GRADLE_VERSION}-bin.zip" && \
    echo "  Gradle ${GRADLE_VERSION} pre-warmed at $DIST_DIR"

# Pre-download the gradle-wrapper.jar matching the distribution above.
RUN mkdir -p /app/gradle/wrapper && \
    curl -fsSL \
      "https://github.com/gradle/gradle/raw/v${GRADLE_VERSION}.0/gradle/wrapper/gradle-wrapper.jar" \
      -o /app/gradle/wrapper/gradle-wrapper.jar

# Install PM2 globally so server.js stays alive on crash / OOM kill.
RUN npm install -g pm2

WORKDIR /app

COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

# pm2-runtime keeps server.js alive in the foreground (required for containers).
CMD ["pm2-runtime", "/app/server.js"]
