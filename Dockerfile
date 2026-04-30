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

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

CMD ["node", "/app/server.js"]
