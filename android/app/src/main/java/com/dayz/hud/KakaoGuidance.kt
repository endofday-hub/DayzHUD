package com.dayz.hud

import android.app.Application
import android.os.Handler
import android.os.Looper
import com.kakaomobility.knsdk.KNLanguageType
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNSearchReqType
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.common.util.FloatPoint
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

// 카카오내비 SDK 안내 한 건을 화면과 분리해 들고 있는다 — 폰 화면(KNNaviView), 키빅 HUD, 차량 화면(AA)이 같이 쓴다.
// 콜백은 폰 화면이 있으면 그대로 넘기고(없어도 안내는 계속), 위치 갱신마다 HUD로 보내고 state를 알린다.
object KakaoGuidance :
    KNGuidance_GuideStateDelegate, KNGuidance_LocationGuideDelegate, KNGuidance_RouteGuideDelegate,
    KNGuidance_SafetyGuideDelegate, KNGuidance_VoiceGuideDelegate, KNGuidance_CitsGuideDelegate {

    // 차량 화면용: HUD로 보낸 값 + 카카오 회전 코드(AA Maneuver 고르기용), 목적지 이름,
    // 지도용 현재 위치(KATEC)·진행 방향·경로
    class State(
        val tbt: Tbt, val rg: Int, val nextRg: Int?, val destination: String,
        val pos: FloatPoint, val angle: Float, val location: KNLocation, val routes: List<KNRoute>?,
    )

    var state: State? = null
        private set
    val listeners = LinkedHashSet<() -> Unit>() // 메인 스레드에서 불림
    var view: KNNaviView? = null // 폰 안내 화면 (없으면 null)

    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var guidance: KNGuidance? = null
    private var destination = ""
    private var lastSent: Tbt? = null
    private var lastRoute = ""
    private val PRIORITY = KNRoutePriority.KNRoutePriority_Recommand

    val active get() = guidance != null

    fun init(app: Application, done: (KNError?) -> Unit) {
        if (ready) return done(null)
        KNSDK.install(app, "${app.filesDir}/knsdk")
        KNSDK.initializeWithAppKey(
            aAppKey = BuildConfig.KAKAO_APP_KEY,
            aClientVersion = BuildConfig.VERSION_NAME,
            aLangType = KNLanguageType.KNLanguageType_KOREAN,
            aCompletion = { err -> main.post { ready = err == null; done(err) } },
        )
    }

    // SDK 내장 장소 검색 (현재 위치 기준). 결과는 메인 스레드로
    fun search(q: String, done: (List<Place>) -> Unit) {
        val here = KNSDK.sharedGpsManager()?.lastValidGpsData?.pos
        // 인자 순서: 검색어, 페이지, 페이지 크기, 종류(전체), x, y (SDK 바이트코드로 확인 — 문서 없음)
        KNSDK.reqSearch(q, 1, 15, KNSearchReqType.KNSearchReqType_1, here?.x?.toInt() ?: 0, here?.y?.toInt() ?: 0) { err, res ->
            main.post {
                if (err != null || res == null) return@post done(emptyList<Place>().also { fail("검색", err) })
                val pois = res.placeResult?.poiList.orEmpty() + res.addressResult?.poiList.orEmpty()
                done(pois.map { p ->
                    // 안내용 진입점(건물 입구 등)이 있으면 그쪽으로
                    val to = p.guidePoints?.firstOrNull()?.pos ?: p.pos
                    Place(p.name, to.x, to.y, p.rnAddress?.ifEmpty { null } ?: p.address.orEmpty())
                })
            }
        }
    }

    // 현재 위치 → to 경로 탐색 후 안내 시작. view가 있으면 그 화면에 붙인다
    fun start(to: Place, simul: Boolean, done: (String?) -> Unit = {}) {
        val here = KNSDK.sharedGpsManager()?.lastValidGpsData?.pos ?: return done("현재 위치 없음 (GPS 대기)")
        log("카카오 경로 탐색 → ${to.name}")
        val start = KNPOI("현위치", here.x.toInt(), here.y.toInt(), null)
        val dest = KNPOI(to.name, to.x, to.y, null)
        KNSDK.makeTripWithStart(start, dest, null, null) { err, trip ->
            if (err != null || trip == null) {
                main.post { done(fail("경로 생성", err)) }
                return@makeTripWithStart
            }
            trip.routeWithPriority(PRIORITY, 0) { err2, _ ->
                main.post {
                    if (err2 != null) return@post done(fail("경로 탐색", err2))
                    begin(trip, to.name, simul)
                    done(null)
                }
            }
        }
    }

    private fun begin(trip: KNTrip, name: String, simul: Boolean) {
        stop()
        val g: KNGuidance = (if (simul) KNSDK.sharedSimulGuidance() else KNSDK.sharedGuidance()) ?: return
        g.guideStateDelegate = this
        g.locationGuideDelegate = this
        g.routeGuideDelegate = this
        g.safetyGuideDelegate = this
        g.voiceGuideDelegate = this
        g.citsGuideDelegate = this
        g.useBackgroundUpdate = true // 폰 화면이 꺼져도 (AA 연결 중) 계속
        guidance = g
        destination = name
        val v = view
        if (v != null) v.initWithGuidance(g, trip, PRIORITY, 0) else g.startWithTrip(trip, PRIORITY, 0)
        log("카카오 안내 시작${if (simul) " (모의주행)" else ""}")
    }

    fun stop() {
        val g = guidance ?: return
        guidance = null
        runCatching { g.stop() }
        end()
    }

    private fun end() {
        guidance = null
        state = null
        send(TmapMapping.ENDED)
        listeners.forEach { it() }
    }

    private fun fail(step: String, err: KNError?) = "카카오 $step 실패: ${err?.code} ${err?.msg}".also(::log)

    private fun log(msg: String) = HudService.log(msg)

    private fun send(t: Tbt) {
        if (t == lastSent) return
        lastSent = t
        HudService.instance?.kivic?.sendTbt(t)
    }

    // ── 안내 콜백 ──

    override fun guidanceGuideStarted(g: KNGuidance) { view?.guidanceGuideStarted(g) }
    override fun guidanceCheckingRouteChange(g: KNGuidance) { view?.guidanceCheckingRouteChange(g) }
    override fun guidanceRouteUnchanged(g: KNGuidance) { view?.guidanceRouteUnchanged(g) }
    override fun guidanceRouteUnchangedWithError(g: KNGuidance, e: KNError) { view?.guidanceRouteUnchangedWithError(g, e) }
    override fun guidanceOutOfRoute(g: KNGuidance) { view?.guidanceOutOfRoute(g) }
    override fun guidanceRouteChanged(
        g: KNGuidance, from: KNRoute, fromLoc: KNLocation, to: KNRoute, toLoc: KNLocation, reason: KNGuideRouteChangeReason,
    ) { view?.guidanceRouteChanged(g) }

    override fun guidanceGuideEnded(g: KNGuidance) {
        log("카카오 안내 종료")
        view?.guidanceGuideEnded(g, false)
        end()
    }

    override fun guidanceDidUpdateRoutes(g: KNGuidance, routes: List<KNRoute>, info: KNMultiRouteInfo?) {
        view?.guidanceDidUpdateRoutes(g, routes, info)
    }

    override fun guidanceDidUpdateIndoorRoute(g: KNGuidance, route: KNRoute?) { view?.guidanceDidUpdateIndoorRoute(g, route) }

    // 위치는 약 1초마다 온다 → 그때마다 현재 안내 전체를 다시 계산해 HUD·차량 화면으로 (같으면 안 보냄)
    override fun guidanceDidUpdateLocation(g: KNGuidance, loc: KNGuide_Location) {
        view?.guidanceDidUpdateLocation(g, loc)
        val here = loc.location ?: return
        val dir = g.routeGuide?.curDirection ?: return
        val next = g.routeGuide?.nextDirection
        val trip = g.trip ?: return
        val safety = g.safetyGuide?.safetiesOnGuide?.firstOrNull { !it.passed && KakaoNaviMapping.sdiCode(it.code.name) != null }
        val lane = g.routeGuide?.lane
        val tbt = KakaoNaviMapping.toTbt(KakaoNaviMapping.Guide(
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
        ))
        send(tbt)
        val gps = loc.gpsMatched
        state = State(
            tbt, dir.rgCode.value, next?.rgCode?.value, destination,
            // 지도엔 GPS가 아니라 경로 위 위치 — 그래야 내 차가 경로선에 딱 붙고 지나온 경로 지우기와 맞는다
            FloatPoint(here.pos.x.toFloat(), here.pos.y.toFloat()), gps.angle.toFloat(), here, g.routesOnGuide,
        )
        listeners.forEach { it() }
    }

    override fun guidanceDidUpdateRouteGuide(g: KNGuidance, r: KNGuide_Route) {
        view?.guidanceDidUpdateRouteGuide(g, r)
        // 거의 매초 같은 내용으로 온다 → 바뀔 때만 기록
        val msg = "카카오 회전: ${r.curDirection?.rgCode} ${r.curDirection?.directionNames} / 다음 ${r.nextDirection?.rgCode}"
        if (msg != lastRoute) log(msg)
        lastRoute = msg
    }

    override fun guidanceDidUpdateSafetyGuide(g: KNGuidance, s: KNGuide_Safety?) { view?.guidanceDidUpdateSafetyGuide(g, s) }
    override fun guidanceDidUpdateAroundSafeties(g: KNGuidance, s: List<KNSafety>?) { view?.guidanceDidUpdateAroundSafeties(g, s) }

    // 폰 화면이 없으면 SDK가 알아서 음성 안내
    override fun shouldPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice, data: MutableList<ByteArray>) =
        view?.shouldPlayVoiceGuide(g, v, data) ?: true

    override fun willPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice) { view?.willPlayVoiceGuide(g, v) }
    override fun didFinishPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice) { view?.didFinishPlayVoiceGuide(g, v) }
    override fun didUpdateCitsGuide(g: KNGuidance, c: KNGuide_Cits) { view?.didUpdateCitsGuide(g, c) }
}
