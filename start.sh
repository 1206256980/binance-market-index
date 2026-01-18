#!/bin/sh

# 准备工作
mkdir -p /app/data/logs/hprof
touch /app/data/logs/backend.log

# 邮件报警函数
send_alert() {
  SUBJECT="[CRITICAL] Binance Index Backend Crashed"
  BODY="The Java backend process has stopped unexpectedly at $(date '+%Y-%m-%d %H:%M:%S'). Nginx is still running, check backend.log."
  
  echo "[SYSTEM] Sending crash alert email to 1206256980@qq.com..."
  
  # 使用 curl 通过 SMTP 发送邮件
  curl --url 'smtps://smtp.qq.com:465' --ssl-reqd \
    --mail-from '1206256980@qq.com' \
    --mail-rcpt '1206256980@qq.com' \
    --user '1206256980@qq.com:tuenjpvwychvgibe' \
    -T - <<EOF
From: 1206256980@qq.com
To: 1206256980@qq.com
Subject: $SUBJECT

$BODY
EOF
  echo "[SYSTEM] Email send command finished."
}

# 清理函数
cleanup() {
  echo "[SYSTEM] Stopping all background processes..."
  kill $(jobs -p) 2>/dev/null
  exit 0
}

trap cleanup SIGINT SIGTERM

echo "[SYSTEM] Starting backend and redirecting to backend.log..."
# 注意：这里去掉了 2405 线程池的并行度设置，先保证基础服务稳定
java -Xms2g -Xmx4g \
     -XX:+UseG1GC \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/app/data/logs/hprof/ \
     -XX:ErrorFile=/app/data/logs/hs_err_pid%p.log \
     -Dlogging.file.path=/app/data/logs/ \
     -jar /app/app.jar > /app/data/logs/backend.log 2>&1 &
JAVA_PID=$!

echo "[SYSTEM] Starting nginx in background..."
nginx &

# 后台监控 Java 进程
(
  while true; do
    if ! kill -0 $JAVA_PID 2>/dev/null; then
      echo "[ALERT] Java Process is DOWN!"
      send_alert
      break 
    fi
    sleep 10
  done
) &

# 前台核心：实时透传日志
echo "[SYSTEM] Now streaming logs from backend.log..."
tail -F /app/data/logs/backend.log
