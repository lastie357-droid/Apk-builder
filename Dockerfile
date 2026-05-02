FROM ubuntu:24.04

# Install build dependencies and runtime tools.
RUN apt-get update && apt-get install -y --no-install-recommends \
        nodejs \
        npm \
        bash \
        python3 \
        python3-pip \
        curl \
        openjdk-17-jdk-headless \
        unzip \
        zip \
        findutils \
        coreutils \
        sed \
        grep \
        gawk \
        git \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Pre-install pyzipper at image build time.
RUN pip3 install --break-system-packages --quiet pyzipper

# Install Android SDK
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
ENV PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"

# Accept SDK licenses
RUN yes | sdkmanager --sdk_root="$ANDROID_HOME" --licenses

# Install SDK components
RUN sdkmanager --sdk_root="$ANDROID_HOME" "platforms;android-36" "build-tools;35.0.0"

# Pre-download Gradle wrapper JAR at image build time
RUN mkdir -p /app/gradle/wrapper && \
    curl -fsSL "https://github.com/gradle/gradle/raw/v8.7.0/gradle/wrapper/gradle-wrapper.jar" \
    -o /app/gradle/wrapper/gradle-wrapper.jar

# Install PM2 globally so server.js stays alive on crash / OOM kill.
RUN npm install -g pm2

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

# pm2-runtime keeps server.js alive in the foreground (required for containers).
CMD ["pm2-runtime", "/app/server.js"]
