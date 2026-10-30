# Kivic SM HUD BLE 프로토콜 (분석 결과)

출처: `Kivic HUD for Tmap` 1.91 APK (`com.kivic.hudcontrol.tmap`) 정적 분석. 실기기 검증 전.

## 왜 안 되나

- 앱은 TMAP 연동 API로 길안내를 받음: `com.skt.tmap.ku`의 `tmap.interlock.intent.action.init` 서비스(EDCService)에 bind
  → `edc.tmap.co.kr` 인증(`modelName="KIVICSM"`, `manufacturerType=22`) → XML TBT 수신 → HUD로 전송
- 끊긴 곳은 **TMAP ↔ Kivic 앱** 구간 (연동 서비스 또는 인증 서버). HUD ↔ 폰 BLE 구간에는 인증이 없음
  → TMAP 데이터 대신 알림 파싱 결과를 넣어 같은 패킷을 보내면 HUD가 표시할 것으로 판단

## 전송 계층: BLE Nordic UART

| 항목 | 값 |
|---|---|
| 기기 이름 | `KIVIC HUD` 포함 |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` |
| 쓰기 (RX) | `6e400002-…` · `WRITE_TYPE_NO_RESPONSE` · **19바이트씩 분할**, `onCharacteristicWrite` 후 다음 조각 |
| 수신 (TX) | `6e400003-…` notify (CCCD `00002902-…` 활성화) |

## 프레임

```
0x02 | escape( command, param1, param2, payload… ) | 0x03
escape: 0x02 / 0x03 / 0x7D → 0x7D, (b ^ 0x7D)
```
- 체크섬 없음. payload는 Java `DataOutputStream` (big-endian, 문자열은 `writeUTF` = u16 길이 + Modified UTF-8)
- command: `2` = 폰→HUD 명령, `3` = HUD→폰 이벤트, `1` = 알림
- command 바이트 0x02가 escape되므로 모든 명령 프레임은 `02 7D 7F …`로 시작

## 연결 순서 (원본 앱 동작)

1. GATT 연결 → 서비스 탐색 → TX notify 활성화
2. `UartConnectionCheck` 전송 — `02 7D 7F 06 00 03`
3. HUD가 `UartConnectionEvent`(3,1,1, payload `int kivicMode`)로 응답
4. `KivicMode(9 = ANDROID_TBT_MODE)` 전송 — `02 7D 7F 07 00 00 00 00 09 03`
5. HUD의 UartConnectionEvent(5초마다)마다 `KeepAlive` — `02 7D 7F 0F 00 03`로 답함, 12초 무응답 시 재연결
   (실기기: 10초 타이머로 보내면 HUD에 "phone connected"가 반복 표시됨)
6. 안내 변경 시 `TBTTmapData` 전송

## TBTTmapData (command 2, param1 18, param2 2)

payload 순서 (그대로 직렬화):

| 타입 | 필드 | 비고 |
|---|---|---|
| bool | isConnected | 안내 연결됨 |
| int | naviDisplayStatus | |
| int | naviStatus | |
| int | posSpeed | 현재 속도 |
| int | totalDistance | 남은 거리(m) |
| long | totalTime | 남은 시간 |
| int | curTbtDistance | 다음 회전까지(m) |
| int | curTbtCode | **TMAP TBT 코드** (아이콘은 HUD 펌웨어가 그림) |
| utf | curTbtName | 교차로/방면 이름 |
| int | nextTbtDistance | |
| int | nextTbtCode | |
| bool | isSafe | 안전운행(카메라) |
| int | safeCode, safeDistance, safeId, safeSpeedLimit | |
| bool | isBlock | 구간단속 |
| int | blockDistance, blockSpeed | |
| bool | isLane | 차로 안내 |
| int | laneDistance | |
| int | laneCount, int[laneCount] laneTurnInfo | |
| int | laneEtcCount, int[] laneEtcInfo | |
| int | laneAvailableCount, int[] laneAvailable | |

안내 종료: `isConnected=false`, `naviDisplayStatus=0`.

## 화면 설정 패킷 (command 2)

원본 앱은 연결 시마다 저장된 설정을 전부 재전송 → HUD가 설정을 유지하지 않는 것으로 추정.

| p1, p2 | payload | 내용 |
|---|---|---|
| 9, 11 | int | 테마 색상: 0 앰버, 1 시안, 2 핑크, 3 옐로, 4 그린 |
| 9, 13 | int | 내비 테마: 0 일반, 1 자동차 |
| 14, 0 | float | 레이아웃 크기 (원본 슬라이더 0 ~ 0.2) |
| 3, 0 | float | 키스톤 (원본 슬라이더 0 ~ 0.1) |
| 22, 0 | int | 화면 방향: 0 가로, 1 뒤집힌 가로 |
| 8, 1 | bool | 전체 화면 |
| 9, 3 | bool | 속도 표시 |
| 9, 12 | bool | 속도 게이지 |
| 9, 10 | int | 속도 색상 ARGB (원본: 흰 -1, 시안 0xFF00FFFF, 초록 0xFF00FF00) |
| 9, 4 / 9, 14 | bool | 시계 표시 / AM·PM |
| 9, 5 | int | 속도 단위 |
| 2, 1 | bool | 자동 밝기 |
| 2, 5 | int | 수동 밝기 (0 ~ 255 추정) |

## 펌웨어 업데이트 (원본 앱 흐름, 2026-10 기준 다운로드 불가)

1. HUD가 `HudVersionEvent`(3,5,0, utf `"모델&시스템버전&BLE버전"`)를 보냄 → 앱이 저장
2. 매니페스트 JSON 다운로드: `https://docs.google.com/uc?export=download&id=0B1Jridvl-H03aERwNzA4bW03dFk`
   - `manifest.<모델>.{system_version, ble_version, url, size}` (+ `ota.<현재버전|default>.{url,size}`)
   - **현재 Google 로그인 요구(비공개/삭제)** → 펌웨어 URL을 얻을 수 없음. Wayback에도 없음
3. `url`에서 `update.dat` 다운로드 (앱 외부 저장소 Downloads)
4. `SoftwareUpdate`(2,10,1, long size, int type 0=HUD/1=OBD) 전송 → HUD가 `SoftwareUpdateEvent`(3,4,1)
5. Wi-Fi Direct(또는 HUD 핫스팟) 연결 후 HUD IP의 **TCP 7980**으로 파일 원본 바이트를 그대로 전송 (헤더·체크섬 없음)
   - 취소: `SoftwareUpdateCancel`

## 공식 자료 (github.com/KivicHud/HudControl)

- `Doc/kr/HUD_SDK_V1.0.pdf`, `HUD_Control_Developer_Guide_V1.3.pdf`: 위 프레임·명령 체계와 일치 (공식 프로토콜 표 포함)
- 하드웨어: 폰 ⇄ BLE(nRF51822) ⇄ UART ⇄ **Rockchip RK3128 (Android)**. 화면 480×240
- HUD는 **설정·시간을 저장하지 않음** → 연결 시 SystemTime(2,1,0: long millis + utf 타임존) 포함 전체 재전송
- HUD가 **5초마다** UartConnectionEvent(3,1,1) 전송, 10초 무응답 = 끊김. 부팅 후 첫 연결 시 kivicMode = -1
- 추가 명령: SpeedWarning(9,9 int, 기본 100), SpeedUnits(9,5: 0 km/h, 1 mph), MinBrightness(2,4: int 51~255 + bool showSetting),
  NotiTimeout(9,1 int초), Display 화면 on/off(9,2 bool), GpsSignalWeak(11,1 bool), HudDisconnect(12,0)
- 알림: Notification(1, 1 전화 / 4 소셜 / 12 음악 / 14 속도, String). 사용 전 DisplayNotification(9,7) + 항목별 설정(9,8) 필요
- `KivicAndroidFramework/libs/kivicCast.jar` + `libKivicCastNative.so` + 샘플: **공식 미러링 SDK** (커스텀 화면 경로)
- `BootLogoChange/`: adb.exe + bootanimation.zip을 USB로 HUD에 넣는 도구 → HUD는 USB ADB 접근 가능 (System 2.1.0 이상)
- 펌웨어 파일은 저장소에 없음

## 실기기 확인 (2026-10-30, Galaxy A90 Android 12 ↔ KIVIC HUD 2.2.1 / BLE 1.0.6)

- 연결 순서 위 그대로 동작. 첫 UartConnectionEvent의 kivicMode = -1(`FF FF FF FF`), 모드 9 전송 후 `00 00 00 09`, 이후 5초마다 반복
- 전원 재투입 시 BLE 칩이 HUD 본체(Android)보다 먼저 켜짐 → 연결은 되지만 이벤트 없음. 본체 부팅 후(~20초) 재연결해야 정상
- TBTTmapData 전송 시 HUD에 회전 아이콘·거리·이름 표시됨 (회전코드 순회 17~201 정상) → **HUD 펌웨어는 TMAP 연동 여부를 따로 확인하지 않음**
- 연결 직후 HUD가 보내는 이벤트 (의미 미확인):
  - `03 02 02` utf `"Unknown"` + utf `"00:00:00:00:00:00"` + `00 09` (Wi-Fi Direct 기기 정보로 추정)
  - `03 03 01` bool true
  - `03 05 00` HudVersion utf `"KIVIC-HUD&2.2.1&1.0.6"`
  - `03 06 00` `00 00 00 00 06` + utf `"Empty network"` + `00 00` (Wi-Fi 상태로 추정)
  - `00 01 01`

## 미확인 (실기기로 확인할 것)

- `curTbtCode` 값별 아이콘. 원본은 TMAP 값을 그대로 넘김 → TMAP EDC SDK 문서의 TurnType 표와 같은 체계
  (11 직진, 12 좌회전, 13 우회전, 14 유턴, 16~19 8·10·2·4시 방향, 31~42 1~12시 방향 …). HUD가 전부 그리는지는 앱의 "회전코드 순회"로 확인
- `naviDisplayStatus` / `naviStatus` 유효 값 (원본은 TMAP XML 값을 그대로 전달)
- `totalTime` 단위 (초/분)
