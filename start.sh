#!/bin/sh

# 准备日志和 Dump 目录
mkdir -p /app/data/logs/hprof

# 清理函数：确保脚本退出时杀掉所有子进程
cleanup() {
  echo "[SYSTEM] Stopping all processes..."
  # 杀掉后台运行的所有任务
  kill $(jobs -p) 2>/dev/null
  exit 1
}

# 捕获终止信号
trap cleanup SIGINT SIGTERM

echo "[SYSTEM] Starting backend with 4G heap and diagnostics..."

# 启动后端 (后台运行并记录 PID)
# 增加 OOM dump 和 错误日志路径，确保护久化到挂载的目录
java -Xms2g -Xmx4g \
     -XX:+UseG1GC \
     -XX:MaxGCPauseMillis=200 \
     -XX:+ExplicitGCInvokesConcurrent \
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/app/data/logs/hprof/ \
     -XX:ErrorFile=/app/data/logs/hs_err_pid%p.log \
     -Dlogging.file.path=/app/data/logs/ \
     -jar /app/app.jar &
JAVA_PID=$!

# 等待后端启动（检查端口 8080）
echo "[SYSTEM] Waiting for backend to be ready on 8080..."
n=0
while [ $n -lt 60 ]; do
  # 使用 netstat 或简单的 sleep 检查。Alpine 镜像通常有 netstat
  if netstat -ltn 2>/dev/null | grep -q :8080; then
    echo "[SYSTEM] Backend is UP!"
    break
  fi
  sleep 1
  n=$((n+1))
done

# 如果后端没起来就退出了，直接终止
if ! kill -0 $JAVA_PID 2>/dev/null; then
  echo "[ERROR] Backend fails to start, exiting..."
  exit 1
fi

echo "[SYSTEM] Starting nginx..."
# 启动 nginx (后台运行并记录 PID)
nginx -g "daemon off;" &
NGINX_PID=$!

# 关键：等待任意一个进程退出。-n 只要有一个退出了，wait 就结束
# 这样如果 Java 挂了，脚本会继续执行下面的 cleanup，从而停止容器
wait -n

echo "[SYSTEM] A critical process has stopped."
cleanup
