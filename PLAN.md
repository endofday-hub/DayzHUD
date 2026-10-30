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
- [x] RGData → TBTTmapData 변환 (TBT 코드·거리·명칭, 속도, SDI→안전운행, 차로) — 회전·거리·제한속도·단속카메라·차로 HUD 표시 확인 2026-10-30
- [x] Foreground Service로 이동: 자동 재연결, KeepAlive (HUD 이벤트마다 응답, 12초 무응답 시 재연결)
- **완료 기준**: TMAP 안내(폰 단독, AA 연결 중 모두) 시 Kivic HUD에 회전/거리 표시

### Phase 2 — 네이버 / 카카오
- 알림 샘플 결과 (2026-10-30, Galaxy A90, 네이버지도 6.11.2.2 / 카카오내비 4.52.0):
  - 폰 단독: 두 앱 모두 고정 문구 알림뿐 ("내비게이션 - 안내 중", "길안내 주행 중") → 회전 정보 없음
  - AA 연결(DHU): **네이버지도**는 알림 extras `androidx.car.app.EXTENSIONS`에 `content_title`=다음 회전 거리("134m"),
    `content_text`=방면/교차로명, `large_bitmap`=회전 아이콘(72×72 흰색) → 아이콘 해시 ↔ TMAP 회전코드 표 필요
  - AA 연결: **카카오내비**는 여전히 회전 정보 없음 ("길안내 주행 중 / 목적지 : …") → 알림 방식 불가
- [x] 네이버 알림 파서 (`NaverMapping`, AA 연결 중만) — HUD 회전·거리 표시, 종료 시 화면 정리 확인 2026-10-30
- [x] 네이버 회전 아이콘 해시 → TMAP 회전코드 표 (6종 수집, 새 아이콘은 앱 로그로 확인 후 추가)
- [x] 카카오내비: 알림엔 회전 정보 없음. AA 안내는 차량 계기판으로만 감(DHU 계기판에서 확인) → 폰에서 못 읽음
- [ ] 카카오내비 HUD 모드 화면 읽기 (`KakaoHudReader` 접근성 + 화살표 캡처 모양 판별, 폰 단독) — 구현 2026-10-30, 실주행 확인·화살표 표 수집 필요
  - 카카오내비는 모의주행 없음, Lockito 가짜 GPS도 걸러냄 → 실주행으로만 시험
  - 불안정하면 대안: KNSDK(카카오내비 SDK)로 앱 내 안내 + HUD (개인용 무료 한도 추정, 사용 신청 필요)
- 네이버 남은 거리·도착 시각·제한속도: AA 화면에만 있음. 접근성 서비스 시험(2026-10-30) → AA 화면은 앱별 비공개 가상 디스플레이(`CarAppService`)라 display 0만 보임 → 불가

### Phase 2-K — 카카오내비 SDK(KNSDK)로 앱 안에서 길안내 → HUD
화면 읽기(`KakaoHudReader`)는 카카오내비 화면이 켜져 있어야 하고 화살표를 그림으로 판별해 불안정할 수 있다.
KNSDK는 내비 엔진을 앱에 넣는 방식이라 회전 코드·거리·카메라·차로를 값으로 받는다 (TMAP EDC처럼 정확).

- 조건: 카카오 디벨로퍼스 앱(네이티브 앱 키, 패키지 `com.dayz.hud` + 디버그 키 해시) — `secrets.properties`의 `KAKAO_APP_KEY` (완료 2026-10-30)
- 비용: 실시간 길안내 무료 일 100,000건(안내 시작 시 1건), 개인·비상업은 별도 신청 없음 (devtalk 담당자 답변)
- 범위 밖: 상업 이용. Android Auto 출력은 K 단계 뒤 A 단계로 (아래)

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| K0 SDK 붙이기 ✅ 2026-10-30 | Maven `devrepo.kakaomobility.com/.../kakao-mobility-android-knsdk-public` + `com.kakaomobility.knsdk:knsdk_ui:1.12.7`, `KNSDK.install` → `initializeWithAppKey`. `KakaoNaviActivity`(SDK가 AndroidX 요구 시 이 화면만 AppCompat)에 `KNNaviView` | 고정 목적지로 경로 탐색·안내 화면 표시, 인증 오류(C302 등) 없음 |
| K1 안내 → HUD ✅ 2026-10-30 (모의주행으로 HUD 표시 확인, 차로 포함 — KNLane turnType 비트 = TMAP laneTurnInfo. HUD는 안내 지점 근처에서만 차로 표시) | `KNGuidance` 델리게이트(GuideState/RouteGuide/Location/Safety)를 `KNNaviView`에 넘기면서 값만 가로챔 → `KakaoNaviMapping` → `Tbt` → `kivic.sendTbt`. 종료 시 `ENDED` | 단위 테스트 + HUD에 회전·거리·남은 거리/시간·속도·카메라 표시 |
| K2 목적지 입력 ✅ 2026-10-30 | SDK 내장 검색 `KNSDK.reqSearch(검색어, 페이지, 크기, 종류, x, y)` (KATEC, REST 키 불필요) → 안내 진입점(`guidePoints`) 우선 → `KNPOI`. 최근 목적지 8개 (`Recents`) | 앱에서 검색해 바로 안내 시작 |
| K3 실주행 | 폰 단독 주행, 화면 꺼짐·다른 앱 전환 중에도 안내 유지 확인 (HudService가 프로세스 유지) | 30분 주행 끊김 없음 |
| K4 선택 | 차로(`KNLane`) → TMAP 차로 비트, 고속도로 정보, 음성 설정. 결과 좋으면 `KakaoHudReader` 제거 | — |

매핑 표 (`KNRGCode` → TMAP TurnType, Kivic이 그리는 코드):
| KNRGCode | 의미 | TMAP |
|---|---|---|
| 0 / 82 / 83 | 직진 / 왼쪽·오른쪽 직진 | 11 |
| 1 / 2 / 3 | 좌 / 우 / 유턴 | 12 / 13 / 14 |
| 5 / 6 | 왼쪽 / 오른쪽 도로 | 118 / 117 |
| 18~29 | 1~12시 방향 | 16~19 중 가까운 것 (8·10·2·4시), 12시=11 |
| 7~12, 42~49 | 고속·도시고속 진출입·분기 | 왼쪽 계열 118, 오른쪽 계열 117 (K1에서 Kivic 순회로 실측) |
| 30~41, 70~81 | 회전교차로 n시 | K1에서 Kivic 코드 확인 후 결정 |
| 100 / 101 / 1000 | 출발 / 도착 / 경유지 | 200 / 201 / (경유지 코드 확인) |

거리: `KNGuide_Location.location.distToLocation(curDirection.location)` (회전까지), 남은 거리·시간은 목적지 위치에 같은 함수(`timeToLocation`).
속도: `gpsMatched`(KNGPSData) — 없으면 HudService GPS 속도.

K0 실측 (2026-10-30)
- `androidx.databinding:viewbinding` 직접 추가 필요 (SDK 의존성 누락), `KNNaviView`는 install·초기화 뒤 생성
- C103 INVALID_TOKEN = 콘솔 네이티브 앱 키에 패키지명·키 해시 누락
- `convertWGS84ToKATEC(경도, 위도)`, 모의주행 `KNSDK.sharedSimulGuidance()` → `initWithGuidance`로 시작
- SDK 내장 장소 검색 `KNSDK.reqSearch` 있음 → K2에 REST 키 불필요할 수 있음
- `KNSDK.makeRendererWithFrame(..., Surface)` 있음 → A3(AA 지도)에 사용
- APK 약 65MB

리스크
- SDK 크기(지도·음성 데이터)·AndroidX 의존 → APK 커짐. 수용
- 모의주행 지원 여부 미확인 → Lockito 시도, 안 되면 실주행만
- KATEC 좌표 변환 방법(KNSDK 유틸 또는 카카오 좌표 변환 API) K2에서 확인

#### Android Auto 출력 (K1 안정화 후)
DayzHUD를 Car App Library 내비 앱(`androidx.car.app.category.NAVIGATION`)으로 등록. KNSDK 안내 값을 그대로 쓴다.
설치: Play 미배포 → 폰 AA 개발자 설정 "알 수 없는 출처" 허용. 시험: DHU (`instrumentcluster = true`로 계기판까지)

| 단계 | 내용 | 완료 기준 |
|---|---|---|
| A1 ✅ 2026-10-30 | `NavigationTemplate` 회전 카드(`RoutingInfo`/`Step`/`Maneuver`)·도착 정보(`TravelEstimate`), `NavigationManager.updateTrip` → 차량 계기판·순정 HUD. 지도 자리엔 큰 화살표·거리 직접 그림(`SurfaceCallback` Canvas) | DHU 화면·계기판에 안내 표시 |
| A2 | 차량 화면에서 목적지 고르기 (최근 목적지 `ListTemplate`, 검색 `SearchTemplate`) | 차에서 안내 시작 |
| A3 ✅ 2026-10-30 | 카카오 지도를 차량 화면에: `KNSDK.makeRendererWithFrame(..., Surface)` (우회 불필요). 경로 `setRoutes`·`cullPassedRoute(loc, false)`, 내 차는 `KNMapMarker`(userLocation은 렌더러에서 안 그려짐, 아래 끝 기준이라 pixelOffset), 위치는 경로 위 `KNLocation.pos` | DHU에서 지도·경로·내 차 확인 |

KNRGCode → AA `Maneuver`: `CarManeuver` (테스트 있음). 안내는 `KakaoGuidance`(화면과 분리)가 들고 폰 화면·HUD·차량 화면이 같이 씀
- 알려진 제약: 앱 재설치(프로세스 재시작) 뒤엔 기존 AA Surface가 막힘(IllegalArgumentException) → AA 재연결 필요

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
