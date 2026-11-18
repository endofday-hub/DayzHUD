package com.dayz.hud

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
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
    // 지도용 현재 위치(KATEC)·진행 방향·경로, 앞쪽 돌발 정보(사고·공사 등)
    class State(
        val tbt: Tbt, val rg: Int, val nextRg: Int?, val destination: String,
        val pos: FloatPoint, val angle: Float, val location: KNLocation, val routes: List<KNRoute>?,
        val event: RoadEvent?,
    )

    class RoadEvent(val title: String, val distance: Int, val icon: Bitmap?)

    private const val EVENT_RANGE = 3_000 // 이 거리 안의 돌발만 차량 화면에

    var state: State? = null
        private set
    val listeners = LinkedHashSet<() -> Unit>() // 메인 스레드에서 불림
    var view: KNNaviView? = null // 폰 안내 화면 (없으면 null)

    private val main = Handler(Looper.getMainLooper())
    private var app: Application? = null
    private var ready = false
    private var lastVia: Place? = null // 재탐색 때 아직 안 지난 경유지 유지

    // SDK 기본은 음악 채널(USAGE_MEDIA) → 차에서 음악과 같은 볼륨·같이 나옴.
    // 내비 안내 채널로 바꾸면 차의 내비 볼륨으로 따로 조절되고, 재생 동안 음악을 줄여 달라고(duck) 요청
    private val NAV_AUDIO = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(NAV_AUDIO).build()

    init {
        KNGuide_Voice.Companion.setUsage(NAV_AUDIO.usage)
        KNGuide_Voice.Companion.setContentType(NAV_AUDIO.contentType)
    }

    // 안내 음성 크기 (내비 볼륨 안에서 비율). 차량 화면 옵션에서 크게·중간·작게
    var volume: Float
        get() = app?.getSharedPreferences("hud", Context.MODE_PRIVATE)?.getFloat("voiceVolume", 1f) ?: 1f
        set(v) { app?.getSharedPreferences("hud", Context.MODE_PRIVATE)?.edit()?.putFloat("voiceVolume", v)?.apply() }

    // 안내 음성 동안 음악 소리 줄이기 (차량 화면 옵션, 기본 켜짐)
    var duck: Boolean
        get() = app?.getSharedPreferences("hud", Context.MODE_PRIVATE)?.getBoolean("voiceDuck", true) ?: true
        set(v) { app?.getSharedPreferences("hud", Context.MODE_PRIVATE)?.edit()?.putBoolean("voiceDuck", v)?.apply() }
    private var guidance: KNGuidance? = null
    private var destination = ""
    private var lastTo: Place? = null // 재탐색용
    private var lastSimul = false
    private var lastSent: Tbt? = null
    private var lastRoute = ""
    private var lastEvent: String? = null
    private val PRIORITY = KNRoutePriority.KNRoutePriority_Recommand

    val active get() = guidance != null

    fun init(app: Application, done: (KNError?) -> Unit) {
        this.app = app
        if (ready) return done(null)
        KNSDK.install(app, "${app.filesDir}/knsdk")
        KNSDK.initializeWithAppKey(
            aAppKey = ApiKeys.kakao(app.getSharedPreferences("hud", Context.MODE_PRIVATE)),
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

    // 지금 자리 근처 검색 (주유소·편의점 등), 가까운 순. 주소 앞에 직선거리
    fun nearby(q: String, done: (List<Place>) -> Unit) {
        val here = state?.pos ?: KNSDK.sharedGpsManager()?.lastValidGpsData?.pos?.let { FloatPoint(it.x.toFloat(), it.y.toFloat()) }
            ?: return done(emptyList())
        search(q) { list ->
            done(list.map { it to Math.hypot((it.x - here.x).toDouble(), (it.y - here.y).toDouble()).toInt() } // KATEC = 미터
                .sortedBy { it.second }
                .map { (p, d) -> p.copy(address = "${if (d < 1000) "${d}m" else "%.1fkm".format(d / 1000.0)} · ${p.address}") })
        }
    }

    // 안내 중이면 지금 목적지 앞에 경유지로 넣어 다시 탐색, 아니면 그곳으로 바로 안내
    fun addVia(p: Place, done: (String?) -> Unit) {
        val to = lastTo
        if (to == null || !active) start(p, lastSimul, done = done) else start(to, lastSimul, p, done)
    }

    // 현재 위치 → (via 거쳐) to 경로 탐색 후 안내 시작. view가 있으면 그 화면에 붙인다
    fun start(to: Place, simul: Boolean, via: Place? = null, done: (String?) -> Unit = {}) {
        // 안내 중이면 경로 위 지금 자리에서 (모의주행 재탐색도 그 자리에서 이어짐)
        val (hx, hy) = state?.pos?.let { it.x.toInt() to it.y.toInt() }
            ?: KNSDK.sharedGpsManager()?.lastValidGpsData?.pos?.let { it.x.toInt() to it.y.toInt() } ?: return done("현재 위치 없음 (GPS 대기)")
        lastTo = to
        lastSimul = simul
        lastVia = via
        log("카카오 경로 탐색 → ${to.name}${via?.let { " (경유 ${it.name})" } ?: ""}")
        val start = KNPOI("현위치", hx, hy, null)
        val dest = KNPOI(to.name, to.x, to.y, null)
        val vias = via?.let { mutableListOf(KNPOI(it.name, it.x, it.y, null)) }
        KNSDK.makeTripWithStart(start, dest, vias, null) { err, trip ->
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
        // 재탐색이면 앞 안내는 조용히 멈춤: end()로 끝을 알리면 AA엔 navigationEnded→Started가 붙어 가고 HUD도 한 번 꺼짐 (DHU: 새 안내까지 꺼짐)
        guidance?.let { runCatching { it.stop() } }
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

    // 같은 목적지로 지금 자리에서 다시 탐색
    fun reroute(done: (String?) -> Unit) {
        val to = lastTo ?: return done("재탐색할 목적지 없음")
        val via = lastVia?.takeIf { guidance?.trip?.passedVias().isNullOrEmpty() } // 이미 지난 경유지는 빼고
        start(to, lastSimul, via, done)
    }

    fun stop() {
        val g = guidance ?: return
        guidance = null
        runCatching { g.stop() }
        end()
    }

    private fun audio() = app?.getSystemService(AudioManager::class.java)

    private fun end() {
        guidance = null
        audio()?.abandonAudioFocusRequest(focus) // 음성 도중 끝나도 음악 볼륨 되돌림
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
            speed = loc.gpsMatched.speed.coerceAtLeast(0), // 모르면 -1이 옴 (모의주행 시작 직후 등)
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
        val event = g.routeGuide?.roadEvents.orEmpty()
            .map { it to here.distToLocation(it.location) }
            .filter { it.second in 0..EVENT_RANGE }
            .minByOrNull { it.second }
            ?.let { (e, d) -> RoadEvent(e.title.orEmpty().ifEmpty { e.desc.orEmpty() }, d, e.icon) }
        if (event != null && event.title != lastEvent) log("카카오 돌발: ${event.title} ${event.distance}m")
        lastEvent = event?.title
        val gps = loc.gpsMatched
        state = State(
            tbt, dir.rgCode.value, next?.rgCode?.value, destination,
            // 지도엔 GPS가 아니라 경로 위 위치 — 그래야 내 차가 경로선에 딱 붙고 지나온 경로 지우기와 맞는다
            FloatPoint(here.pos.x.toFloat(), here.pos.y.toFloat()), gps.angle.toFloat(), here, g.routesOnGuide, event,
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
    override fun shouldPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice, data: MutableList<ByteArray>): Boolean {
        val play = view?.shouldPlayVoiceGuide(g, v, data) ?: true
        v.setVolume(volume)
        return play
    }

    override fun willPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice) {
        view?.willPlayVoiceGuide(g, v)
        if (duck) audio()?.requestAudioFocus(focus)
    }

    override fun didFinishPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice) {
        view?.didFinishPlayVoiceGuide(g, v)
        audio()?.abandonAudioFocusRequest(focus)
    }
    override fun didUpdateCitsGuide(g: KNGuidance, c: KNGuide_Cits) { view?.didUpdateCitsGuide(g, c) }
}
