#!/bin/sh
set -u

java -jar /app/platform-api.jar &
java_pid=$!
nginx -g 'daemon off;' &
nginx_pid=$!

stop_children() {
    trap '' TERM INT QUIT
    kill -QUIT "$nginx_pid" 2>/dev/null || true
    kill -TERM "$java_pid" 2>/dev/null || true
    wait "$nginx_pid" 2>/dev/null || true
    wait "$java_pid" 2>/dev/null || true
}

trap 'stop_children; exit 0' TERM INT QUIT

# 任一服务退出时停止另一个服务，让 Compose 重启整个后台容器。
while kill -0 "$java_pid" 2>/dev/null && kill -0 "$nginx_pid" 2>/dev/null; do
    sleep 1
done

printf '%s\n' 'Java or Nginx exited unexpectedly' >&2
stop_children
exit 1
