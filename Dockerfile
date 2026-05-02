FROM ubuntu:24.04

# Prevent interactive prompts during apt installs.
ENV DEBIAN_FRONTEND=noninteractive

# Install build dependencies and runtime tools.
# libstdc++6, zlib1g, libncurses6 are required by the pre-built aapt2,
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
# Copy only the Gradle wrapper files first so this expensive download step is
# cached by Docker even when source files change. Then run `./gradlew --version`
# using the REAL wrapper so it downloads, extracts, and registers the
# distribution in GRADLE_USER_HOME with the exact internal hash it expects.
# This eliminates the re-download that happened on every runtime build.
ENV GRADLE_USER_HOME=/root/.gradle

WORKDIR /app

COPY gradlew gradlew
COPY gradle/ gradle/

RUN chmod +x /app/gradlew && \
    ./gradlew --version --no-daemon 2>&1 | tail -5 && \
    echo "Gradle distribution cached at $GRADLE_USER_HOME"

# Install PM2 globally so server.js stays alive on crash / OOM kill.
RUN npm install -g pm2

# Copy the rest of the project.
COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

# pm2-runtime keeps server.js alive in the foreground (required for containers).
CMD ["pm2-runtime", "/app/server.js"]
