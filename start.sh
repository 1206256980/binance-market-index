#!/bin/sh

# 启动后端 (后台运行) - JVM堆内存1G
echo "Starting backend with 1G heap..."
java -Xms512m -Xmx1024m -jar /app/app.jar &

# 等待后端启动
sleep 5

# 启动 nginx (前台运行)
echo "Starting nginx..."
nginx -g "daemon off;"
