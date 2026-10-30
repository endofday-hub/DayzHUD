#!/usr/bin/env bash
# Kivic HUD 읽기 전용 덤프: HUD에는 아무것도 쓰지 않는다 (install/push/remount 없음)
# 사용: bash tools/hud_dump.sh [출력폴더]   (Git Bash)
set -u
export MSYS_NO_PATHCONV=1 # Git Bash가 /system/... 인자를 C:/... 로 바꾸는 것 방지
ADB="${ADB:-$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe}"
OUT="${1:-hud-dump}/$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT/apk" "$OUT/part"
a() { "$ADB" "$@"; }

echo "HUD 대기 중… (USB 연결, 장치 관리자에 기기가 보여야 함)"
a wait-for-device
a devices -l | tee "$OUT/devices.txt"

# 기본 정보
a shell getprop > "$OUT/getprop.txt"
a shell id > "$OUT/id.txt"
a shell cat /proc/cmdline /proc/partitions > "$OUT/partitions.txt" 2>&1
a shell 'ls -l /dev/block/by-name /dev/block/platform/*/by-name' > "$OUT/by-name.txt" 2>&1
a shell df > "$OUT/df.txt" 2>&1
a pull /system/build.prop "$OUT/" 2>&1

# 화면·실행 중 앱 (HUD 화면을 그리는 런처 찾기)
a exec-out screencap -p > "$OUT/screen.png"
a shell dumpsys activity activities > "$OUT/activities.txt" 2>&1
a shell dumpsys package > "$OUT/dumpsys-package.txt" 2>&1
a shell pm list packages -f > "$OUT/packages.txt"

# 기본 AOSP 외 APK 회수: 서드파티 + 이름에 kivic/hud/launcher 포함
{ a shell pm list packages -f -3; grep -iE 'kivic|hud|launcher' "$OUT/packages.txt"; } |
  tr -d '\r' | sed 's/^package://' | sort -u | while read -r line; do
    apk="${line%=*}"; pkg="${line##*=}"
    a pull "$apk" "$OUT/apk/$pkg.apk" < /dev/null 2>&1 | tail -1
  done

# 파티션 원본 백업: adbd가 root일 때만 가능 (dd로 읽기만 함)
if grep -q 'uid=0' "$OUT/id.txt"; then
  echo "root adbd → 파티션 백업"
  a shell 'ls /dev/block/by-name 2>/dev/null || ls /dev/block/platform/*/by-name' | tr -d '\r' |
    while read -r p; do
      [ -z "$p" ] && continue
      case "$p" in userdata) continue ;; esac # 개인 데이터·용량 큼 → 제외
      echo "  $p"
      a exec-out "dd if=\$(ls /dev/block/by-name/$p /dev/block/platform/*/by-name/$p 2>/dev/null | head -1) 2>/dev/null" \
        > "$OUT/part/$p.img" < /dev/null
    done
else
  echo "root 아님 → 파티션 백업 생략 (APK·설정만 회수)"
fi

echo "완료: $OUT"
ls -la "$OUT" "$OUT/apk" "$OUT/part"
