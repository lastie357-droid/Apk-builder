FROM node:22-alpine

RUN apk add --no-cache bash python3 curl

WORKDIR /app

COPY . .

RUN chmod +x /app/build.sh

ENV PORT=7000

EXPOSE 7000

CMD ["node", "/app/server.js"]
