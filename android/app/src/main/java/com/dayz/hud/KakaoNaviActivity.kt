package com.dayz.hud

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_CitsGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNMultiRouteInfo
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.KNGuide_Safety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Camera
import com.kakaomobility.knsdk.guidance.knguidance.safetyguide.objects.KNSafety_Section
import com.kakaomobility.knsdk.guidance.knguidance.voiceguide.KNGuide_Voice
import com.kakaomobility.knsdk.trip.kntrip.KNTrip
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import com.kakaomobility.knsdk.ui.view.KNNaviView

// 카카오내비 SDK(KNSDK)로 앱 안에서 길안내 (PLAN.md Phase 2-K).
// 안내 콜백은 화면(KNNaviView)에 그대로 넘기고, 값은 가로채 HUD로 보낸다 (KakaoNaviMapping).
// K0: 목적지는 인텐트 좌표(WGS84) 고정, EXTRA_SIMUL이면 모의주행
class KakaoNaviActivity : AppCompatActivity(),
    KNGuidance_GuideStateDelegate, KNGuidance_LocationGuideDelegate, KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate, KNGuidance_VoiceGuideDelegate, KNGuidance_CitsGuideDelegate {

    private lateinit var naviView: KNNaviView
    private var lastSent: Tbt? = null
    private var lastRoute = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KNNaviView는 SDK install·초기화 뒤에 만들어야 한다 (먼저 만들면 크래시)
        init { err ->
            if (err != null) return@init fail("초기화", err)
            naviView = KNNaviView(this)
            setContentView(naviView)
            route()
        }
    }

    private fun init(done: (KNError?) -> Unit) {
        KNSDK.install(application, "$filesDir/knsdk")
        KNSDK.initializeWithAppKey(
            aAppKey = BuildConfig.KAKAO_APP_KEY,
            aClientVersion = BuildConfig.VERSION_NAME,
            aLangType = KNLanguageType.KNLanguageType_KOREAN,
            aCompletion = { runOnUiThread { done(it) } },
        )
    }

    private fun route() {
        val here = KNSDK.sharedGpsManager()?.lastValidGpsData?.pos ?: return log("현재 위치 없음 (GPS 대기)")
        val lon = intent.getDoubleExtra(EXTRA_LON, 126.9707) // 기본: 서울역
        val lat = intent.getDoubleExtra(EXTRA_LAT, 37.5547)
        val goal = KNSDK.convertWGS84ToKATEC(lon, lat)
        log("카카오 경로 탐색: 현재 KATEC(${here.x.toInt()}, ${here.y.toInt()}) → ($lon, $lat) KATEC(${goal.x.toInt()}, ${goal.y.toInt()})")
        val start = KNPOI("현위치", here.x.toInt(), here.y.toInt(), null)
        val dest = KNPOI(intent.getStringExtra(EXTRA_NAME) ?: "서울역", goal.x.toInt(), goal.y.toInt(), null)
        KNSDK.makeTripWithStart(start, dest, null, null) { err, trip ->
            if (err != null || trip == null) return@makeTripWithStart fail("경로 생성", err)
            trip.routeWithPriority(PRIORITY, 0) { err2, _ ->
                runOnUiThread {
                    if (err2 != null) return@runOnUiThread fail("경로 탐색", err2)
                    start(trip)
                }
            }
        }
    }

    private fun start(trip: KNTrip) {
        val simul = intent.getBooleanExtra(EXTRA_SIMUL, false)
        val g: KNGuidance = (if (simul) KNSDK.sharedSimulGuidance() else KNSDK.sharedGuidance()) ?: return log("안내 객체 없음")
        g.guideStateDelegate = this
        g.locationGuideDelegate = this
        g.routeGuideDelegate = this
        g.safetyGuideDelegate = this
        g.voiceGuideDelegate = this
        g.citsGuideDelegate = this
        naviView.initWithGuidance(g, trip, PRIORITY, 0)
        log("카카오 안내 시작${if (simul) " (모의주행)" else ""}")
    }

    private fun fail(step: String, err: KNError?) = log("카카오 $step 실패: ${err?.code} ${err?.msg}")

    private fun log(msg: String) = HudService.log(msg)

    // ── 안내 콜백: 화면(KNNaviView)에 그대로 넘기고, 위치 갱신 때 값을 HUD로 ──

    override fun guidanceGuideStarted(g: KNGuidance) = naviView.guidanceGuideStarted(g)
    override fun guidanceCheckingRouteChange(g: KNGuidance) = naviView.guidanceCheckingRouteChange(g)
    override fun guidanceRouteUnchanged(g: KNGuidance) = naviView.guidanceRouteUnchanged(g)
    override fun guidanceRouteUnchangedWithError(g: KNGuidance, e: KNError) = naviView.guidanceRouteUnchangedWithError(g, e)
    override fun guidanceOutOfRoute(g: KNGuidance) = naviView.guidanceOutOfRoute(g)
    override fun guidanceRouteChanged(
        g: KNGuidance, from: KNRoute, fromLoc: KNLocation, to: KNRoute, toLoc: KNLocation, reason: KNGuideRouteChangeReason,
    ) = naviView.guidanceRouteChanged(g)

    override fun guidanceGuideEnded(g: KNGuidance) {
        log("카카오 안내 종료")
        naviView.guidanceGuideEnded(g, false)
        send(TmapMapping.ENDED)
    }

    override fun guidanceDidUpdateRoutes(g: KNGuidance, routes: List<KNRoute>, info: KNMultiRouteInfo?) =
        naviView.guidanceDidUpdateRoutes(g, routes, info)

    override fun guidanceDidUpdateIndoorRoute(g: KNGuidance, route: KNRoute?) = naviView.guidanceDidUpdateIndoorRoute(g, route)

    // 위치는 약 1초마다 온다 → 그때마다 현재 안내 전체를 다시 계산해 HUD로 (같으면 안 보냄)
    override fun guidanceDidUpdateLocation(g: KNGuidance, loc: KNGuide_Location) {
        naviView.guidanceDidUpdateLocation(g, loc)
        val here = loc.location ?: return
        val dir = g.routeGuide?.curDirection ?: return
        val next = g.routeGuide?.nextDirection
        val trip = g.trip ?: return
        val safety = g.safetyGuide?.safetiesOnGuide?.firstOrNull { !it.passed && KakaoNaviMapping.sdiCode(it.code.name) != null }
        val lane = g.routeGuide?.lane
        send(KakaoNaviMapping.toTbt(KakaoNaviMapping.Guide(
            rg = dir.rgCode.value,
            turnDistance = here.distToLocation(dir.location),
            name = dir.directionNames?.firstOrNull() ?: dir.nodeName.orEmpty(),
            nextRg = next?.rgCode?.value,
            nextDistance = next?.let { here.distToLocation(it.location) } ?: 0,
            remainDistance = trip.remainDist(),
            remainSeconds = trip.remainTime(),
            speed = loc.gpsMatched.speed,
            safety = safety?.let {
                KakaoNaviMapping.Safety(
                    it.code.name,
                    here.distToLocation(it.location),
                    (it as? KNSafety_Camera)?.speedLimit ?: 0,
                    (it as? KNSafety_Section)?.sectionAvrSpeed ?: 0,
                )
            },
            lanes = lane?.laneInfos.orEmpty().map {
                KakaoNaviMapping.Lane(
                    it.turnType.toInt(), it.pocketType.toInt(), it.busType.toInt(),
                    it.facilityType.toInt(), it.highlightType.toInt(), it.suggest.toInt(),
                )
            },
            laneDistance = lane?.let { here.distToLocation(it.location) } ?: 0,
        )))
    }

    private fun send(t: Tbt) {
        if (t == lastSent) return
        lastSent = t
        HudService.instance?.kivic?.sendTbt(t)
    }

    override fun onDestroy() {
        if (lastSent?.connected == true) send(TmapMapping.ENDED) // 안내 화면을 닫으면 HUD 정리
        super.onDestroy()
    }

    override fun guidanceDidUpdateRouteGuide(g: KNGuidance, r: KNGuide_Route) {
        naviView.guidanceDidUpdateRouteGuide(g, r)
        // 거의 매초 같은 내용으로 온다 → 바뀔 때만 기록
        val msg = "카카오 회전: ${r.curDirection?.rgCode} ${r.curDirection?.directionNames} / 다음 ${r.nextDirection?.rgCode}"
        if (msg != lastRoute) log(msg)
        lastRoute = msg
    }

    override fun guidanceDidUpdateSafetyGuide(g: KNGuidance, s: KNGuide_Safety?) = naviView.guidanceDidUpdateSafetyGuide(g, s)
    override fun guidanceDidUpdateAroundSafeties(g: KNGuidance, s: List<KNSafety>?) = naviView.guidanceDidUpdateAroundSafeties(g, s)
    override fun shouldPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice, data: MutableList<ByteArray>) = naviView.shouldPlayVoiceGuide(g, v, data)
    override fun willPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice) = naviView.willPlayVoiceGuide(g, v)
    override fun didFinishPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice) = naviView.didFinishPlayVoiceGuide(g, v)
    override fun didUpdateCitsGuide(g: KNGuidance, c: KNGuide_Cits) = naviView.didUpdateCitsGuide(g, c)

    companion object {
        const val EXTRA_LON = "lon"
        const val EXTRA_LAT = "lat"
        const val EXTRA_NAME = "name"
        const val EXTRA_SIMUL = "simul"
        private val PRIORITY = KNRoutePriority.KNRoutePriority_Recommand
    }
}
