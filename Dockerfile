FROM node:22-alpine

WORKDIR /app

# Install frpc
RUN apk add --no-cache wget \
    && wget -q https://github.com/fatedier/frp/releases/download/v0.61.1/frp_0.61.1_linux_amd64.tar.gz \
    && tar -xzf frp_0.61.1_linux_amd64.tar.gz \
    && mv frp_0.61.1_linux_amd64/frpc /usr/local/bin/frpc \
    && rm -rf frp_0.61.1_linux_amd64*

COPY . .

WORKDIR /app/backend
RUN npm install --ignore-scripts

WORKDIR /app/frontend
RUN npx --yes http-server --version || npm install -g http-server

COPY frp/frpc.toml /etc/frp/frpc.toml

EXPOSE 8080

CMD frpc -c /etc/frp/frpc.toml & \
    npx http-server /app/frontend -p 3000 & \
    cd /app/backend && npm start
