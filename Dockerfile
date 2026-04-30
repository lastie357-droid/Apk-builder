FROM node:22-bookworm-slim

# build.sh needs: bash, python3 + pip (no PEP 668 restrictions on Debian/Ubuntu),
# curl, openjdk-17, unzip/zip for Android cmdline-tools and APK rebuilds,
# and findutils/coreutils for GNU semantics the script relies on.
RUN apt-get update && apt-get install -y --no-install-recommends \
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
    && rm -rf /var/lib/apt/lists/*

# Pre-install pyzipper at image build time — no flags needed on Debian/Ubuntu.
RUN pip3 install --quiet pyzipper

# Install PM2 globally so the Node server stays alive across crashes / OOM kills.
RUN npm install -g pm2

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

# pm2-runtime keeps server.js alive without forking to a background daemon
# (required for containers — pm2 daemon mode would exit immediately).
CMD ["pm2-runtime", "/app/server.js"]
