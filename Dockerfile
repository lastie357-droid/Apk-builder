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
