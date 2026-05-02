FROM node:20-slim

ENV DEBIAN_FRONTEND=noninteractive

RUN apt-get update && apt-get install -y --no-install-recommends \
        bash \
        python3 \
        python3-pip \
        curl \
        openjdk-17-jdk-headless \
        unzip \
        zip \
        git \
        ca-certificates \
        libstdc++6 \
        libc6 \
        zlib1g \
        libncurses6 \
    && rm -rf /var/lib/apt/lists/*

RUN pip3 install --break-system-packages --quiet pyzipper

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

RUN yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses

RUN sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0"

RUN echo "==> Checking native build-tool binaries..." && \
    "$ANDROID_HOME/build-tools/35.0.0/aapt2" version && \
    echo "    aapt2 OK" && \
    "$ANDROID_HOME/build-tools/35.0.0/zipalign" 2>/dev/null; \
    echo "    zipalign OK"

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

ENV GRADLE_USER_HOME=/root/.gradle

WORKDIR /app

COPY gradlew gradlew
COPY gradle/ gradle/

RUN chmod +x /app/gradlew && \
    ./gradlew --version --no-daemon 2>&1 | tail -5 && \
    echo "Gradle distribution cached at $GRADLE_USER_HOME"

COPY . .

RUN chmod +x /app/build.sh
