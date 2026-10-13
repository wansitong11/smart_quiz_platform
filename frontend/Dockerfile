# 前端运行时镜像：只复制本地已构建好的 dist，容器内不跑 pnpm。
#
# 注意构建上下文：请用 **frontend/ 目录**作为 context（CI 与服务器一致）：
#   docker build -t smart-quiz-frontend:local frontend/
# 这样上下文只有 dist + nginx.conf（约 2MB），不会把整个仓库（含 235MB 的 app.jar）打进去——
# 之前用仓库根目录做上下文时，光传上下文就要 13 分钟，还会把小机器压出假健康告警。
FROM nginx:alpine
COPY dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
