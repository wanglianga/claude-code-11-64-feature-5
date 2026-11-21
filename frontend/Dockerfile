# syntax=docker/dockerfile:1
# ---------- 构建阶段 ----------
FROM node:22-alpine AS build
WORKDIR /build
RUN npm config set registry https://registry.npmmirror.com
COPY package.json package-lock.json* ./
RUN npm install --no-audit --no-fund
COPY . .
RUN npm run build

# ---------- 运行阶段：nginx 托管静态资源并反代后端 ----------
FROM nginx:1.27-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /build/dist /usr/share/nginx/html
EXPOSE 80
