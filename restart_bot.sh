#!/bin/bash
SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$SCRIPT_DIR" || exit 1

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$SCRIPT_DIR/restart.log"
}

: > "$SCRIPT_DIR/restart.log"
log "=== 开始重启流程 ==="

OLD_PID=$(pgrep -f "java.*-jar $SCRIPT_DIR/bot.jar")
log "检测到旧进程PID: $OLD_PID"

if [ -n "$OLD_PID" ]; then
  log "发送SIGTERM到进程 $OLD_PID"
  kill "$OLD_PID"
  
  for i in {1..15}; do
    if ! ps -p "$OLD_PID" > /dev/null; then
      log "进程 $OLD_PID 已正常退出"
      break
    fi
    sleep 1
    log "等待进程退出 ($i/15)..."
  done
  
  if ps -p "$OLD_PID" > /dev/null; then
    log "强制终止进程 $OLD_PID"
    kill -9 "$OLD_PID"
    sleep 2
  fi
fi

log "清理文件锁..."
lsof -t "$SCRIPT_DIR/bot.jar" > /tmp/bot.lock.pids 2>/dev/null
if [ -s /tmp/bot.lock.pids ]; then
  log "发现锁定进程: $(tr '\n' ' ' < /tmp/bot.lock.pids)"
  while read -r pid; do
    if ps -p "$pid" > /dev/null; then
      log "终止残留进程 $pid"
      kill -9 "$pid" 2>/dev/null && sleep 0.5
    fi
  done < /tmp/bot.lock.pids
fi
rm -f /tmp/bot.lock.pids
chmod 644 "$SCRIPT_DIR/bot.jar" 2>/dev/null

clean_old_log() {
  local log_file="$SCRIPT_DIR/bot.log"
  
  [ ! -f "$log_file" ] && log "未找到旧日志文件" && return 0

  # 解除日志文件锁
  log "解除日志文件锁定..."
  lsof -t "$log_file" | xargs -r kill -9 2>/dev/null
  sleep 1  # 等待文件句柄释放

  for i in {1..5}; do
    if rm -f "$log_file"; then
      log "✅ 成功删除旧日志"
      return 0
    fi
    log "第${i}次删除失败，修改权限后重试..."
    chmod 666 "$log_file" 2>/dev/null  # 修复权限问题
    sleep 1
  done

  if [ -f "$log_file" ]; then
    log "⚠️ 无法删除，执行内容截断"
    : > "$log_file"  # 清空内容
  fi
}

log "开始清理旧日志..."
clean_old_log

log "启动新进程..."
nohup setsid java \
  -Dfile.encoding=UTF-8 \
  -Duser.timezone=Asia/Shanghai \
  -jar "$SCRIPT_DIR/bot.jar" \
  --spring.config.location=file:"$SCRIPT_DIR/config/" \
  >> "$SCRIPT_DIR/bot.log" 2>&1 &

MAX_WAIT=30
SUCCESS=0
for ((i=1; i<=$MAX_WAIT; i++)); do
  NEW_PID=$(pgrep -f "java.*-jar $SCRIPT_DIR/bot.jar")
  if [ -n "$NEW_PID" ]; then
    log "启动成功！新PID: $NEW_PID"
    SUCCESS=1
    break
  fi
  
  if tail -n 5 "$SCRIPT_DIR/bot.log" | grep -q -E "ERROR|Exception|main.* exited"; then
    log "检测到启动错误，终止等待"
    break
  fi
  
  sleep 1
done

if [ $SUCCESS -eq 0 ]; then
  log "❌ 重启失败！最后10行日志："
  tail -n 10 "$SCRIPT_DIR/bot.log" >> "$SCRIPT_DIR/restart.log"
  exit 1
fi

log "=== 重启完成 ==="
exit 0