FROM node:22-alpine

WORKDIR /app

RUN apk add --no-cache wget \
    && wget -q https://github.com/fatedier/frp/releases/download/v0.61.1/frp_0.61.1_linux_amd64.tar.gz \
    && tar -xzf frp_0.61.1_linux_amd64.tar.gz \
    && mv frp_0.61.1_linux_amd64/frps /usr/local/bin/frps \
    && mv frp_0.61.1_linux_amd64/frpc /usr/local/bin/frpc \
    && rm -rf frp_0.61.1_linux_amd64*

COPY . .

WORKDIR /app/backend
RUN npm install --ignore-scripts

RUN mkdir -p /etc/frp
COPY frps/frps.toml /etc/frp/frps.toml
COPY frpc/frpc.toml /etc/frp/frpc.toml

EXPOSE 7000 8080

CMD ["node", "/app/backend/server.js"]
