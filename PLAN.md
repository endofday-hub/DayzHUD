# DayzHUD 개발 계획

내비 앱의 길안내를 읽어 **보유 중인 Kivic SM HUD**(지원 종료된 차량용 HUD)에 BLE로 다시 띄운다.
차량: Android Auto 있음, 순정 HUD 없음.

## 1. 구조

```
[TMAP 앱] ──EDC SDK (RGData 콜백)──┐
                                   ├─▶ [DayzHUD 앱] ──BLE Nordic UART──▶ [Kivic SM HUD]
[네이버 지도 / 카카오내비] ──알림──┘     NavState → TBTTmapData 패킷       (아이콘·숫자는 HUD가 직접 그림)
```

- HUD 프로토콜: `docs/kivic-protocol.md` (원본 앱 APK 분석 결과)
- ESP32 / OLED: **불필요** (Kivic HUD가 디스플레이)

## 2. 데이터 소스별 방식

| 앱 | 방식 | 근거 |
|---|---|---|
| **TMAP** | **EDC SDK** (`com.tmapmobility.tmap:tmap-edc-sdk`) — TMAP 앱의 실시간 RGData(TBT 회전코드·거리·속도·SDI·차로) 수신 | TMAP 회전코드 = Kivic `curTbtCode` 체계 → 거의 그대로 전달 |
| 네이버 지도 | 알림 파싱 | 공개 SDK는 지도 표시용, 길안내 연동 없음 |
| 카카오내비 | 알림 파싱 | KNSDK는 자체 내비 엔진(앱 안에서 주행해야 함, AA 불가) → 보류 |

## 3. 핵심 리스크

| 리스크 | 대응 |
|---|---|
| HUD 펌웨어가 TBT 패킷을 받아줄지 (TMAP 연동 여부를 따로 확인할 가능성) | Phase 0 키빅 테스트로 최우선 확인 |
| Kivic 회전코드별 아이콘 / `naviStatus` 값 / 시간 단위 미확인 | 테스트 화면의 코드 순회로 실측 |
| EDC SDK 무료 한도 일 20건 (단위 미확인, "1회 인증 = 앱 실행 중 유효") | 앱 실행당 1회 인증 유지, 실측으로 한도 확인 |
| EDC 인증의 `userKey` 발급 방식 | 개발자 센터 가이드 확인 |
| AA 연결 중 EDC / 알림 수신 여부 | Phase 0~1에서 실차 확인 |
| 원본 Kivic 앱이 HUD에 먼저 붙으면 연결 불가 | 원본 앱 삭제 또는 블루투스 연결 해제 |
| 안드로이드 백그라운드 제한 | Foreground Service + 배터리 최적화 예외 |

## 4. 단계

### Phase 0 — 검증
- [x] 알림 덤프 앱 (네이버/카카오 샘플 수집용)
- [x] Kivic APK 분석 → 프로토콜 문서화
- [ ] **키빅 BLE 테스트**: 연결 → 연결확인 → TBT 모드 → 테스트 TBT 표시 (앱 내 테스트 버튼)
- [ ] 회전코드 순회로 아이콘 확인, `naviDisplayStatus`/`naviStatus` 값 확정
- [ ] TMAP 개발자 센터 가입 → `clientID` / `apiKey` 발급, `userKey` 방식 확인
- [ ] 네이버/카카오 알림 샘플 수집 (폰 단독 / AA 연결 중)

### Phase 1 — MVP: TMAP → Kivic
- [x] EDC SDK 연동 (initialize → registerDataCallback) — 실기기 확인 2026-10-30. 등록은 서비스 연결 후(1초 뒤)·안내 시작 시 다시 해야 함
  - 실제 RGData 키: allHighwayTbtList, approachingGuidePoint, averageSpeed, currentCourseAngle, currentRoadName, currentRoutePlan, destinationLatitude/Longitude/Name, firstTBTInfo, secondTBTInfo, gpsState, hasAlternativeRoute, hasTbtInfo, hipassLaneCount, isCaution, isNightMode, isOnHighway, isRoadHighWay, isShadeArea, laneAvailableInfo, laneBubbleList, laneCount, laneDistance, laneEtcInfo, laneTurnInfo, limitSpeed, matchedLatitude/Longitude, nGoPosDist, nGoPosTime, noLocationSignal, remainDistanceToDestinationInMeter, remainTimeToDestinationInSec, remainViaPointSize, showLane, showSDI, speedInKmPerHour
- [ ] RGData → TBTTmapData 변환 (TBT 코드·거리·명칭, 속도, SDI→안전운행, 차로)
- [x] Foreground Service로 이동: 자동 재연결, KeepAlive (HUD 이벤트마다 응답, 12초 무응답 시 재연결)
- **완료 기준**: TMAP 안내(폰 단독, AA 연결 중 모두) 시 Kivic HUD에 회전/거리 표시

### Phase 2 — 네이버 / 카카오
- [ ] 알림 파서 (수집 샘플 기반 단위 테스트)
- [ ] 회전 정보 → TMAP 회전코드 변환표

### Phase 3 — 실사용 안정화
- [ ] 시동 시 자동 연결, 삼성 배터리 정책 테스트
- [ ] 안내 종료/앱 종료 시 HUD 화면 정리 (`isConnected=false`)

### Phase 4 — 선택
- HUD 설정(밝기·키스톤 등) — 원본 앱 패킷 존재 (`docs/kivic-protocol.md`)
- 안내 없을 때 속도 표시

## 5. 테스트

- 패킷 인코딩: `KivicPacketTest` (JVM 단위 테스트, 프레임/escape 고정값)
- 파서: 수집 알림 샘플 → 기대 NavState
- 실차: Phase마다 주행 1회 (폰 단독 + AA)

## 6. 저장소

```
DayzHUD/
├─ PLAN.md
├─ docs/kivic-protocol.md
└─ android/   # Kotlin, minSdk 29, AndroidX 미사용
```

## 7. 주의

- 개인 사용 목적. HUD 프로토콜은 보유 기기 상호운용 목적의 분석이며 디컴파일 코드는 저장소에 넣지 않음
- 표시 정보는 회전/거리 중심, 운전 주시 분산 최소화
