#!/usr/bin/env bash
# ==============================================================================
# 轻量巡检：内存压力 / 容器内存 / postgres 重启 / load 超阈值就告警
#
# 为什么需要：2026-10-14 的卡顿是"用户先发现、我们后知后觉"——
#   postgres 一天之内 12 次后端崩溃重建、宿主机内存压力 PSI 到 95%，全程没有任何告警。
#
# 安装（服务器上执行一次）：
#   chmod +x /opt/smart-quiz/deploy/health-check.sh
#   ( crontab -l 2>/dev/null; echo "*/5 * * * * /opt/smart-quiz/deploy/health-check.sh >> /opt/smart-quiz/logs/health-check.log 2>&1" ) | crontab -
#   （脚本会自己读 /opt/smart-quiz/deploy/env.prod，cron 里不用再 source）
#
# 通知：设置 ALERT_WEBHOOK_URL（钉钉/飞书/企业微信机器人地址）即推送；
#       不设置就只写日志（/opt/smart-quiz/logs/health-check.log），保持零依赖。
#
# 阈值可用环境变量覆盖，便于测试：PSI_THRESHOLD / MEM_THRESHOLD / LOAD_THRESHOLD / ALERT_WEBHOOK_URL
# ==============================================================================
set -u

APP_DIR=${APP_DIR:-/opt/smart-quiz}
STATE_FILE=${STATE_FILE:-/var/tmp/smart-quiz-health.state}
LOG_FILE=${LOG_FILE:-$APP_DIR/logs/health-check.log}

# 自己读一遍 env.prod：这样 ALERT_WEBHOOK_URL（以及阈值覆盖）从一处配置就生效，
# cron 里不用再写 ". env.prod" 这种容易出错的长命令
if [ -f "$APP_DIR/deploy/env.prod" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$APP_DIR/deploy/env.prod"
  set +a
fi

PSI_THRESHOLD=${PSI_THRESHOLD:-30}       # 内存压力 some avg60 百分比
MEM_THRESHOLD=${MEM_THRESHOLD:-85}       # 容器内存占上限百分比
LOAD_THRESHOLD=${LOAD_THRESHOLD:-6}      # 1 分钟 load（2 核机器上 6 已经很吃力）

mkdir -p "$(dirname "$LOG_FILE")"

now=$(date '+%F %T')
alerts=()

# 1) 宿主机内存压力（PSI）：some avg60 是"至少一个任务因内存停顿"的时间占比
psi_some=$(awk '/^some/ {for (i=1;i<=NF;i++) if ($i ~ /^avg60=/) {sub("avg60=","",$i); print $i}}' /proc/pressure/memory 2>/dev/null || echo 0)
if [ -n "$psi_some" ] && awk -v v="$psi_some" -v t="$PSI_THRESHOLD" 'BEGIN{exit !(v>t)}'; then
  alerts+=("内存压力偏高：PSI some avg60=${psi_some}%（阈值 ${PSI_THRESHOLD}%）")
fi

# 2) 各容器内存占上限
while IFS='|' read -r name pct; do
  value=${pct%\%}
  if awk -v v="$value" -v t="$MEM_THRESHOLD" 'BEGIN{exit !(v>t)}'; then
    alerts+=("容器 ${name} 内存 ${pct} 超过阈值 ${MEM_THRESHOLD}%")
  fi
done < <(docker stats --no-stream --format '{{.Name}}|{{.MemPerc}}' 2>/dev/null)

# 3) postgres 是否又崩了（统计日志里 reinitializing 的次数，与上次比较）
restarts=$(docker logs smart-quiz-postgres 2>&1 | grep -c "all server processes terminated")
restarts=${restarts:-0}
if [ -f "$STATE_FILE" ]; then
  prev=$(cat "$STATE_FILE" 2>/dev/null || echo "$restarts")
  if [ "$restarts" -gt "$prev" ]; then
    alerts+=("postgres 又重建了集群：累计 ${restarts} 次（上次巡检 ${prev} 次）——查 docker logs smart-quiz-postgres 里的 broken pipe / connection reset")
  fi
else
  # 首次运行只记录基线，不告警（否则会把历史次数当成新发生的）
  echo "首次运行：记录 postgres 重建次数基线 = ${restarts}"
fi
echo "$restarts" > "$STATE_FILE"

# 4) load
load1=$(awk '{print $1}' /proc/loadavg)
if awk -v v="$load1" -v t="$LOAD_THRESHOLD" 'BEGIN{exit !(v>t)}'; then
  alerts+=("load1=${load1} 超过阈值 ${LOAD_THRESHOLD}")
fi

if [ ${#alerts[@]} -eq 0 ]; then
  exit 0
fi

message="[smart-quiz] 巡检告警 $(hostname) ${now}
$(printf ' - %s\n' "${alerts[@]}")"

echo "$message" >> "$LOG_FILE"
logger -t smart-quiz-health "$(echo "$message" | tr '\n' ' ')" 2>/dev/null || true

# 可选：推送到机器人 webhook（同时兼容钉钉/飞书/企业微信的字段名，各自会忽略不认识的字段）
if [ -n "${ALERT_WEBHOOK_URL:-}" ]; then
  payload=$(printf '{"msgtype":"text","text":{"content":"%s"},"msg_type":"text","content":{"text":"%s"}}' \
    "$(echo "$message" | sed ':a;N;$!ba;s/\n/\\n/g')" "$(echo "$message" | sed ':a;N;$!ba;s/\n/\\n/g')")
  curl -sS -m 10 -H 'Content-Type: application/json' -d "$payload" "$ALERT_WEBHOOK_URL" >/dev/null 2>&1 \
    || echo "ALERT ${now} webhook 推送失败" >> "$LOG_FILE"
fi
