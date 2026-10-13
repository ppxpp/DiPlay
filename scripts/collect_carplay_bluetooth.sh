#!/bin/bash
set -euo pipefail
serial="${1:-220739d4f21e56b6}"
output_dir="${2:-/tmp/carplay-bluetooth-$(date +%Y%m%d-%H%M%S)}"
adb -s "$serial" get-state >/dev/null
fingerprint="$(adb -s "$serial" shell getprop ro.build.fingerprint | tr -d '\r')"
if [[ "$fingerprint" != 'Android/tamago_3T_uart/tamago_3T_uart:7.1.2/2.0.0-rfp-rc6/1:user/release-keys' ]]; then
    echo '设备不是已分析的先锋车机，停止采集。' >&2
    exit 1
fi
mkdir -p "$output_dir"
adb -s "$serial" shell getprop ro.build.fingerprint > "$output_dir/firmware.txt"
adb -s "$serial" shell dumpsys jp.pioneer.ceam.Blueforce.manage help > "$output_dir/blueforce.txt" 2>&1
adb -s "$serial" shell dumpsys IOSMgr -a > "$output_dir/iosmgr.txt" 2>&1
adb -s "$serial" shell dumpsys bluetooth_manager > "$output_dir/android-bluetooth.txt" 2>&1
adb -s "$serial" shell service list > "$output_dir/services.txt"
adb -s "$serial" shell logcat -d -v threadtime | rg -i 'blueforce|bluetec|iosmgr|iap2|sdp|eir|ssp|pairing|serialport' > "$output_dir/bluetooth-logcat.txt" || true
adb -s "$serial" pull /sdcard/Android/data/com.shilapi.xcertplay.btprobe/files/bluetooth-negotiation.txt "$output_dir/app-report.txt" || true
echo "采集结果：$output_dir"
