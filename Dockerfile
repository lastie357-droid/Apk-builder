FROM alpine:latest

# Install all build.sh and server.js dependencies via apk.
# nodejs + npm  — to run server.js and pm2
# python3 + py3-pip — pyzipper for AES-256 module encryption
# openjdk17     — Gradle / Android build chain
# curl + unzip + zip — Android SDK cmdline-tools download and APK rebuild
# findutils + coreutils + sed + grep + gawk + bash — GNU semantics build.sh relies on
# git           — optional, used by some SDK tooling
RUN apk add --no-cache \
        nodejs \
        npm \
        bash \
        python3 \
        py3-pip \
        curl \
        openjdk17 \
        unzip \
        zip \
        findutils \
        coreutils \
        sed \
        grep \
        gawk \
        git

# Pre-install pyzipper at image build time.
# Alpine marks Python as externally managed (PEP 668), so --break-system-packages
# is required. This is safe because we own the image.
RUN pip install --break-system-packages --quiet pyzipper

# Install PM2 globally so server.js stays alive on crash / OOM kill.
RUN npm install -g pm2

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

# pm2-runtime keeps server.js alive in the foreground (required for containers).
CMD ["pm2-runtime", "/app/server.js"]
