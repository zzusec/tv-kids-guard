#!/usr/bin/env bash
# Read-only Android TV pre-check. No install, provisioning, account removal, or reset.
# Usage: bash tools/device_precheck.sh [adb serial or IPv4 address]

set -u -o pipefail

SERIAL_ARG=()
if [ "$#" -gt 1 ]; then
  printf '用法：%s [adb 序列号或电视 IPv4 地址]\n' "$0" >&2
  exit 1
fi
if [ "$#" -eq 1 ]; then
  SERIAL="$1"
  if [[ "$SERIAL" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    SERIAL="${SERIAL}:5555"
  fi
  SERIAL_ARG=(-s "$SERIAL")
fi
ADB=(adb "${SERIAL_ARG[@]}")
PACKAGE="com.family.tvguard"

command -v adb >/dev/null 2>&1 || { printf '未找到 adb\n' >&2; exit 1; }
"${ADB[@]}" get-state >/dev/null 2>&1 || {
  printf '未找到已授权连接。先运行 adb devices；网络连接由家长另行开启。\n' >&2
  exit 1
}

prop() {
  "${ADB[@]}" shell getprop "$1" | tr -d '\r'
}

printf '=== 电视儿童管控只读预检 ===\n'
printf '厂商：%s\n' "$(prop ro.product.manufacturer)"
printf '型号：%s\n' "$(prop ro.product.model)"
SDK=$(prop ro.build.version.sdk)
printf 'Android：%s（API %s）\n' "$(prop ro.build.version.release)" "$SDK"
printf '固件：%s\n' "$(prop ro.build.display.id)"

FEATURES=$("${ADB[@]}" shell pm list features) || {
  printf '系统功能读取失败，不能判断设备管理资格。\n' >&2
  exit 1
}
HAS_ADMIN=false
if printf '%s\n' "$FEATURES" | tr -d '\r' | grep -qx 'feature:android.software.device_admin'; then
  HAS_ADMIN=true
  printf '\n设备管理员功能：支持\n'
else
  printf '\n设备管理员功能：不支持（固件未声明 android.software.device_admin）\n'
  printf '不能按标准方式开通 Device Owner；仅有 device_policy 服务不代表功能可用。\n'
  printf '恢复出厂不会据此补上固件功能，请勿因此重置电视。\n'
fi

printf '\n--- 初始化状态（已完成不等于 ADB 必定不可开通）---\n'
"${ADB[@]}" shell settings get secure user_setup_complete
"${ADB[@]}" shell settings get global device_provisioned

printf '\n--- 用户 ---\n'
"${ADB[@]}" shell pm list users

printf '\n--- 账户数量（不输出账户名称或凭据）---\n'
if ACCOUNT_DUMP=$("${ADB[@]}" shell dumpsys account); then
  COUNTS=$(printf '%s\n' "$ACCOUNT_DUMP" | tr -d '\r' | grep -E '^[[:space:]]*Accounts:[[:space:]]*[0-9]+[[:space:]]*$' || true)
  if [ -n "$COUNTS" ]; then
    printf '%s\n' "$COUNTS"
  else
    printf '未能确认账户数量；不能把未知按 0 个处理。\n'
  fi
else
  printf '账户查询失败；不能把未知按 0 个处理。\n'
fi
unset ACCOUNT_DUMP

printf '\n--- 当前设备管理状态 ---\n'
"${ADB[@]}" shell dumpsys device_policy

printf '\n--- 管控应用 ---\n'
APP_PATH=$("${ADB[@]}" shell pm path "$PACKAGE") || true
if [ -n "$APP_PATH" ]; then
  printf '%s\n' "$APP_PATH"
  "${ADB[@]}" shell dumpsys package "$PACKAGE" | grep -E 'versionCode=|versionName=' || true
else
  printf '未安装 %s，当前不存在应用防护。\n' "$PACKAGE"
fi

printf '\n--- 当前锁定任务 ---\n'
"${ADB[@]}" shell dumpsys activity activities | grep -i 'lockTaskMode' || true

printf '\n=== 结论 ===\n'
if [ "$SDK" = 19 ] || [ "$SDK" = 20 ]; then
  printf '该 Android 版本没有本应用所需的系统级 Lock Task／防卸载／防强停接口。\n'
  printf '不要执行 dpm set-device-owner；普通版计时与密码保护仍需实机核验权限和后台保活。\n'
  exit 2
fi
if [ "$HAS_ADMIN" != true ]; then
  printf '该固件不能走标准 Device Owner 防卸载／防强停方案。\n'
  printf '普通版只能在权限有效时用家长密码保护设置与卸载入口，不能保证系统拒绝卸载或强停。\n'
  exit 2
fi
printf '支持设备管理不等于已可开通：还须核对已有 Owner、多用户、账户和厂商实现。\n'
printf '本脚本没有进行安装、设置 Owner、删除账户、改初始化标志或恢复出厂。\n'
printf '只有家长明确授权后，才考虑执行以下写操作，并用 dumpsys device_policy 核验结果：\n'
printf '  adb %s shell dpm set-device-owner %s/.GuardDeviceAdmin\n' "${SERIAL_ARG[*]}" "$PACKAGE"
printf '不要为试错清除数据或重置电视。\n'
