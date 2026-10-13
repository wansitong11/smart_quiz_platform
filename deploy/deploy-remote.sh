#!/usr/bin/env bash
# ==============================================================================
# 服务器端发布脚本（由 GitHub Actions 通过 SSH 调用，也可以手工执行）
#
# 用法：
#   APP_IMAGE=registry.cn-hangzhou.aliyuncs.com/ns/smart-quiz-app:v1 \
#   FRONTEND_IMAGE=registry.cn-hangzhou.aliyuncs.com/ns/smart-quiz-frontend:v1 \
#   bash deploy/deploy-remote.sh
#
# 流程：同步代码 → 记录当前镜像（用于回滚）→ 拉新镜像 → 重启 → 健康检查
#      健康检查失败自动回滚到上一个镜像标签并以非 0 退出。
# ==============================================================================
set -euo pipefail

APP_DIR="${APP_DIR:-/opt/smart-quiz}"
ENV_FILE="${ENV_FILE:-deploy/env.prod}"
BRANCH="${BRANCH:-main}"
COMPOSE_FILE="docker-compose.prod.yml"

cd "$APP_DIR"

if [[ ! -f "$ENV_FILE" ]]; then
  echo "[deploy] 缺少 $ENV_FILE，请先从 deploy/env.prod.example 复制并填写" >&2
  exit 1
fi

compose() {
  docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" "$@"
}

# ---------- 1. 同步编排文件与 nginx 模板 ----------
# 只同步 git 跟踪的文件；deploy/env.prod、deploy/certbot 都是 gitignore 的，不会被覆盖
if [[ -d .git ]]; then
  echo "[deploy] 同步代码到 origin/$BRANCH"
  git fetch --prune origin "$BRANCH"
  git checkout --force "$BRANCH"
  git reset --hard "origin/$BRANCH"
fi

# ---------- 2. 记录当前镜像，便于回滚 ----------
PREVIOUS_APP_IMAGE="$(grep -E '^APP_IMAGE=' "$ENV_FILE" | tail -1 | cut -d= -f2- || true)"
PREVIOUS_FRONTEND_IMAGE="$(grep -E '^FRONTEND_IMAGE=' "$ENV_FILE" | tail -1 | cut -d= -f2- || true)"

set_env_var() {
  local key="$1" value="$2"
  if grep -qE "^${key}=" "$ENV_FILE"; then
    # 使用 | 作为分隔符，避免镜像地址里的 / 干扰
    sed -i "s|^${key}=.*|${key}=${value}|" "$ENV_FILE"
  else
    printf '\n%s=%s\n' "$key" "$value" >> "$ENV_FILE"
  fi
}

TARGET_APP_IMAGE="${APP_IMAGE:-$PREVIOUS_APP_IMAGE}"
TARGET_FRONTEND_IMAGE="${FRONTEND_IMAGE:-$PREVIOUS_FRONTEND_IMAGE}"

if [[ -n "${APP_IMAGE:-}" ]]; then
  set_env_var APP_IMAGE "$APP_IMAGE"
fi
if [[ -n "${FRONTEND_IMAGE:-}" ]]; then
  set_env_var FRONTEND_IMAGE "$FRONTEND_IMAGE"
fi

echo "[deploy] 目标镜像: app=$TARGET_APP_IMAGE frontend=$TARGET_FRONTEND_IMAGE"

# ---------- 3. 拉镜像并重启 ----------
echo "[deploy] 登录并拉取镜像"
compose pull app frontend

echo "[deploy] 重启服务"
compose up -d --remove-orphans

# ---------- 4. 健康检查 ----------
wait_healthy() {
  local container="$1" timeout="${2:-180}" waited=0 status
  while (( waited < timeout )); do
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "$container" 2>/dev/null || echo missing)"
    case "$status" in
      healthy|running) echo "[deploy] $container 状态: $status"; return 0 ;;
      unhealthy|exited|dead|missing) echo "[deploy] $container 状态异常: $status"; return 1 ;;
    esac
    sleep 5
    waited=$((waited + 5))
  done
  echo "[deploy] $container 等待超时（${timeout}s）" >&2
  return 1
}

rollback() {
  echo "[deploy] 健康检查失败，回滚到上一个镜像" >&2
  if [[ -n "$PREVIOUS_APP_IMAGE" ]]; then
    set_env_var APP_IMAGE "$PREVIOUS_APP_IMAGE"
  fi
  if [[ -n "$PREVIOUS_FRONTEND_IMAGE" ]]; then
    set_env_var FRONTEND_IMAGE "$PREVIOUS_FRONTEND_IMAGE"
  fi
  compose pull app frontend || true
  compose up -d --remove-orphans || true
  echo "[deploy] 已回滚到 app=$PREVIOUS_APP_IMAGE frontend=$PREVIOUS_FRONTEND_IMAGE" >&2
}

if ! wait_healthy smart-quiz-app 240 || ! wait_healthy smart-quiz-gateway 60; then
  compose logs --tail 80 app || true
  rollback
  exit 1
fi

# ---------- 5. 收尾 ----------
# 清理悬空镜像，避免系统盘被历史版本吃满
docker image prune -f --filter "dangling=true" >/dev/null 2>&1 || true

echo "[deploy] 发布完成: app=$TARGET_APP_IMAGE"
