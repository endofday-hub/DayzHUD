#!/usr/bin/env bash
# Android Auto 시험 (PC DHU + USB 폰): 빌드·설치 → DHU 재시작 → 폰에서 카카오 모의주행(최근 목적지 첫 줄) → DHU 창 캡처
# 사용: bash tools/aa_test.sh [대기초=30]   결과: $TEMP/dayzhud/dhu_*.png (816x519 = 차량 화면, 616x639 = 계기판)
# 전제: 폰 AA 개발자 설정의 "헤드 유닛 서버" 켜짐 + "알 수 없는 출처" 허용, 폰 잠금 해제, 최근 목적지 1개 이상
# 좌표(773,1567 모의주행 버튼 / 300,520 최근 목적지 첫 줄)는 1080x2400 폰 기준
set -u
ROOT=$(cd "$(dirname "$0")/.." && pwd)
ADB="$LOCALAPPDATA/Android/Sdk/platform-tools/adb.exe"; export MSYS_NO_PATHCONV=1
cd "$ROOT/android"
export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/jdk-17.0.21.21-hotspot}"
./gradlew -q assembleDebug 2>&1 | grep -E "^e:" && exit 1
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk >/dev/null && echo installed
# 재설치하면 기존 AA 화면(Surface)이 막히므로 DHU도 다시 띄운다
powershell -NoProfile -Command "\$d=\"\$env:LOCALAPPDATA\Android\Sdk\extras\google\auto\"; Get-Process desktop-head-unit -ErrorAction SilentlyContinue | Stop-Process -Force; Start-Sleep 2; Start-Process -FilePath \"\$d\desktop-head-unit.exe\" -ArgumentList '-c \"$(cygpath -w "$ROOT/tools/dhu_cluster.ini")\"' -WorkingDirectory \$d"
"$ADB" forward tcp:5277 tcp:5277 >/dev/null
sleep 12
"$ADB" logcat -c
"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.dayz.hud/.MainActivity >/dev/null; sleep 6
"$ADB" shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n com.dayz.hud/.MainActivity >/dev/null; sleep 2
for i in 1 2 3 4; do "$ADB" shell input swipe 540 600 540 2000 120; done; sleep 1
"$ADB" shell input tap 773 1567; sleep 10
"$ADB" shell input keyevent KEYCODE_BACK; sleep 1   # 키보드 닫기
"$ADB" shell input tap 300 520; sleep "${1:-30}"
"$ADB" logcat -d -s DayzHUD:I AndroidRuntime:E | grep -E "차량|카카오 (경로|안내)|FATAL|Caused" | cut -c33-220
powershell -NoProfile -ExecutionPolicy Bypass -File "$(cygpath -w "$ROOT/tools/dhu_shot.ps1")"
