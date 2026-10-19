# 项目部署文档

这个目录存放与「把项目部署上线」相关的全部文档。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [生产部署手册.md](./生产部署手册.md) | **部署入口（当前有效）**：从零部署 / 版本更新 / 回滚 / 备份 / 验收，含可直接交给 AI 执行的清单 |
| [运维速查.md](./运维速查.md) | **日常运维入口**：环境一览、架构、日志、查数据、发版、备份、排障 |
| [生产访问与安全演进.md](./生产访问与安全演进.md) | 数据库/生产环境的访问方式、企业级做法对照，以及 6 项可独立实施的改进清单 |
| [阿里云部署方案.md](./阿里云部署方案.md) | 通用部署方案：选型对比、服务器准备、各项配置改动清单、验收清单 |
| [登录鉴权与发布方案.md](./登录鉴权与发布方案.md) | Spring Security + JWT 双 Token + RBAC 的设计与实现说明，以及 CI/CD 流水线设计 |

## 当前线上环境（实况）

> 部署与发布以 [生产部署手册.md](./生产部署手册.md) 为准；`阿里云部署方案.md` 是通用方案与推导过程，其中「在服务器上构建镜像」「SNI 分流」等章节对应的是**最初那台内存不足的服务器**，当前这台香港服务器不需要这些。

| 项 | 值 |
| --- | --- |
| 服务器 | 阿里云轻量应用服务器（**香港**），2 核 / 1.87 GiB / 40 GB（内存与 swap 的收口过程见 `运维速查.md` §3.2） |
| 公网 IP | `8.210.200.94` |
| 站点 | https://quiz.stonev2ray.top |
| DNS | Cloudflare，`quiz` A 记录 → 上述 IP，**DNS only（灰云）** |
| 部署目录 | `/opt/smart-quiz` |
| 部署方式 | **产物式**：本地编译 jar + 前端 dist → 传到服务器 → 服务器只做 `COPY` 打包 |
| 443 归属 | 应用独占（该服务器上没有其他服务占端口，**不需要 SNI 分流**） |
| 旧服务器 | `47.84.95.219`（新加坡，只跑 Xray 代理，与本应用无关，未改动） |

## 架构

```
Internet
   │ 80 / 443
   ▼
[gateway] nginx          唯一对公网暴露的服务：TLS 终止、80 → 443 跳转
   │
   ▼
[frontend] nginx         静态资源 + 反代 /api、/ws
   │
   ▼
[app] Spring Boot 8080   JWT 鉴权 + RBAC
   ├── [postgres] pgvector      ┐
   ├── [redis]                  ├── 只在 Docker 内网，公网不可达
   └── [rustfs]                 ┘
[certbot]                证书自动续期
```

## 为什么用「产物式部署」

这台服务器只有 1.87 GiB 内存，**跑不动 Gradle 构建**（后端编译要 2~3 GB，必然 OOM）。所以：

1. 本地（或以后的 CI）编译出 `app.jar` 和 `frontend/dist`；
2. 传到服务器的 `/opt/smart-quiz`；
3. 服务器上只执行 `docker build` —— 用 `deploy/server/Dockerfile.app` 这种**只 COPY 产物**的文件，不编译。

对应文件：

- `deploy/server/Dockerfile.app`（后端；构建上下文＝仓库根，因为要 COPY 里面的 `app/build/libs/app.jar`）
  与 `frontend/Dockerfile`（前端；构建上下文＝`frontend/`，只含 dist + nginx.conf，约 2MB）
- `deploy/docker-compose.server.yml`（低内存覆盖：内存上限、JVM 压缩参数、回环端口）

具体发版命令见 [运维速查.md](./运维速查.md) 的「发版流程」。

## 未使用的部件

- `deploy/sni-split/`：让应用和 Xray 共用 443 的 SNI 分流方案。当前香港服务器独立占用 443，**用不到**；保留是为了将来若要把应用和代理合并到一台机器时可以直接用。
- `.github/workflows/deploy.yml`：GitHub Actions 发布流水线。需要 ACR 与一批 Secrets，**目前未接入**，发版走手工流程。
