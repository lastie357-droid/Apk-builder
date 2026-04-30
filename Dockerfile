FROM node:22-alpine

# build.sh needs: bash, python3 (+pip for pyzipper), curl, openjdk17 for gradle,
# unzip/zip for the Android cmdline-tools and APK rebuild, and findutils/coreutils
# for the GNU semantics the script relies on.
RUN apk add --no-cache \
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

# Pre-install pyzipper at image build time (Alpine's Python is externally managed
# via PEP 668, so --break-system-packages is required; this is safe because we
# own the image and want pyzipper globally available for build.sh).
RUN pip install --break-system-packages --quiet pyzipper

# Install PM2 globally so the Node server stays alive across crashes / OOM kills.
RUN npm install -g pm2

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

# Use pm2-runtime instead of node directly — it keeps server.js alive on crash
# without forking to a background daemon (required for containers).
CMD ["pm2-runtime", "/app/server.js"]
