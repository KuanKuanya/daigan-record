#!/usr/bin/env bash
# 本地测试模拟器的启停脚本。
#
#   ./tools/dev-emulator.sh start    启动模拟器并等待 boot 完成
#   ./tools/dev-emulator.sh stop     关闭模拟器
#   ./tools/dev-emulator.sh status   查看状态
#   ./tools/dev-emulator.sh logs     跟踪模拟器日志
#
# 首次使用需要创建 AVD：
#   sdkmanager "system-images;android-36;google_apis;arm64-v8a"
#   avdmanager create avd -n daigan_test \
#     -k "system-images;android-36;google_apis;arm64-v8a" -d pixel_6

set -euo pipefail

AVD_NAME="${DAIGAN_AVD:-daigan_test}"
ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
EMULATOR="$ANDROID_HOME/emulator/emulator"
ADB="$ANDROID_HOME/platform-tools/adb"
SERIAL="${DAIGAN_SERIAL:-emulator-5554}"

# 内存与核数按历史测试记录配置：4 GiB / 4 核，且不加载快照。
# 资源不足时模拟器会出现 System UI ANR，导致界面回归偶发失败。
EMU_ARGS=(
  -avd "$AVD_NAME"
  -no-snapshot
  -no-boot-anim
  -gpu swiftshader_indirect
  -memory 4096
  -cores 4
  -no-audio
)

require() {
  if [ ! -x "$1" ]; then
    echo "缺少 $1" >&2
    echo "请先安装 Android SDK 命令行工具与系统镜像（见本文件头部注释）" >&2
    exit 1
  fi
}

start() {
  require "$EMULATOR"; require "$ADB"
  if "$ADB" devices | grep -q "^${SERIAL}[[:space:]]"; then
    echo "模拟器已在运行：$SERIAL"
    return 0
  fi
  echo "启动 $AVD_NAME ..."
  nohup "$EMULATOR" "${EMU_ARGS[@]}" -no-window > /tmp/daigan-emulator.log 2>&1 &
  echo "等待 boot 完成 ..."
  for _ in $(seq 1 60); do
    if [ "$("$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n ')" = "1" ]; then
      echo "就绪：$SERIAL"
      return 0
    fi
    sleep 5
  done
  echo "启动超时，查看 /tmp/daigan-emulator.log" >&2
  return 1
}

stop() {
  require "$ADB"
  "$ADB" -s "$SERIAL" emu kill 2>/dev/null || true
  echo "已请求关闭 $SERIAL"
}

status() {
  require "$ADB"
  "$ADB" devices -l
  if "$ADB" -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; then
    echo "boot 已完成"
  else
    echo "未 boot 完成或未连接"
  fi
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  status) status ;;
  logs) tail -f /tmp/daigan-emulator.log ;;
  *) echo "用法：$0 {start|stop|status|logs}" >&2; exit 2 ;;
esac
