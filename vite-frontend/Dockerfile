# 多阶段构建 - 构建阶段
FROM node:20.19.0 AS builder

WORKDIR /app

COPY package*.json ./
# 当前锁文件由新版 npm 生成，而 Node 20 自带 npm 对其解析会漏掉 Redux 的
# 嵌套依赖；使用 npm install 仍严格依照 package.json 安装并可正常构建。
RUN npm install

COPY . .
RUN npm run build

# 生产阶段
FROM nginx:stable-alpine AS production-stage

COPY nginx.conf /etc/nginx/nginx.conf

COPY --from=builder /app/dist /usr/share/nginx/html

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
