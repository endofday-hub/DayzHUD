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
import com.kakaomobility.knsdk.KNRouteAvoidOption
import com.kakaomobility.knsdk.KNRoutePriority
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.KNSearchReqType
import com.kakaomobility.knsdk.common.objects.KNError
import com.kakaomobility.knsdk.common.objects.KNPOI
import com.kakaomobility.knsdk.common.util.FloatPoint
import com.kakaomobility.knsdk.guidance.knguidance.KNDriveGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_CitsGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_GuideStateDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_LocationGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_RouteGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_SafetyGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuidance_VoiceGuideDelegate
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideFrequencyMode
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideRouteChangeReason
import com.kakaomobility.knsdk.guidance.knguidance.citsguide.KNGuide_Cits
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.guidance.knguidance.locationguide.KNGuide_Location
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.KNGuide_Route
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNHighwayRG
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNHighwayRGType
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNHighwayRG_SA
import com.kakaomobility.knsdk.guidance.knguidance.routeguide.objects.KNHighwayRG_TG
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
    // 지도용 현재 위치(KATEC)·진행 방향·경로, 앞쪽 돌발 정보(사고·공사 등), 고속도로 앞쪽 시설(가까운 순)
    // 안전운행이면 경로·회전 없음 (location·routes null, rg 0)
    class State(
        val tbt: Tbt, val rg: Int, val nextRg: Int?, val destination: String,
        val pos: FloatPoint, val angle: Float, val location: KNLocation?, val routes: List<KNRoute>?,
        val event: RoadEvent?, val highway: List<Highway>,
    )

    class RoadEvent(val title: String, val distance: Int, val icon: Bitmap?)

    // 고속도로 IC·JC·요금소(TG)·휴게소(SA)·졸음쉼터(RA). note: 휴게소면 그다음 휴게소 거리, 요금소면 요금
    class Highway(val kind: String, val name: String, val distance: Int, val note: String?)

    private const val EVENT_RANGE = 3_000 // 이 거리 안의 돌발만 차량 화면에
    private const val HIGHWAY_COUNT = 2 // 차량 화면에 쌓는 고속도로 시설 수

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

    // 목적지를 고르면 경로 선택 화면부터 (끄면 바로 안내, 지금 고른 경로 종류로). 폰 메인 화면 설정
    const val ROUTE_PICK = "routePick"
    // 최소 안내: 회전 안내 음성을 SDK '최소' 빈도로 (카메라 등 안전 안내는 그대로)
    const val MINIMAL = "guideMinimal"

    private val prefs get() = app?.getSharedPreferences("hud", Context.MODE_PRIVATE)
    val routePick get() = prefs?.getBoolean(ROUTE_PICK, true) ?: true
    var minimal: Boolean
        get() = prefs?.getBoolean(MINIMAL, false) ?: false
        set(v) { prefs?.edit()?.putBoolean(MINIMAL, v)?.apply(); applyFreq() }
    private var freqDefault: Pair<KNGuideFrequencyMode, KNGuideFrequencyMode>? = null // SDK 기본 (일반도로, 고속도로)

    // 안내 중이면 바로 반영 (폰 설정에서 바꿔도 불러 줌)
    fun applyFreq() {
        val g = guidance ?: return
        val d = freqDefault ?: (g.directionFreqModeNormalWay to g.directionFreqModeHighWay).also { freqDefault = it }
        val rare = KNGuideFrequencyMode.KNGuideFrequencyMode_Rare
        g.directionFreqModeNormalWay = if (minimal) rare else d.first
        g.directionFreqModeHighWay = if (minimal) rare else d.second
    }

    // 안전운행: 경로 없이 카메라·구간단속·속도 (SDK 안전 운행 모드)
    var safeMode = false
        private set
    private var safeLogged = false

    private var guidance: KNGuidance? = null
    private var destination = ""
    var destAddress = "" // 차량 화면 내 차 아래 글용
        private set
    private var lastLaneColor: String? = null
    private var lastTo: Place? = null // 재탐색용
    private var lastSimul = false
    private var lastSent: Tbt? = null
    private var lastRoute = ""
    private var lastEvent: String? = null
    private var lastHighway = ""
    private var inSection = false
    // 고른 경로 종류 (재탐색·SDK 자동 재탐색도 이걸로). 경로 선택에서 바뀜
    private var priority = KNRoutePriority.KNRoutePriority_Recommand
    private var avoid = 0

    // 경로 선택: 고른 종류 하나와 그 탐색 결과 (출발하면 이 trip으로 안내)
    class RouteChoice(
        val label: String, val priority: KNRoutePriority, val avoid: Int,
        val trip: KNTrip, val route: KNRoute, val to: Place,
    )

    // 경로 선택에 내놓는 종류 (이 순서로 보임). 무료도로 = 추천 + 유료도로 피하기
    private val CHOICES = listOf(
        Triple("추천", KNRoutePriority.KNRoutePriority_Recommand, 0),
        Triple("고속도로 우선", KNRoutePriority.KNRoutePriority_HighWay, 0),
        Triple("무료도로", KNRoutePriority.KNRoutePriority_Recommand, KNRouteAvoidOption.KNRouteAvoidOption_Fare.value),
        Triple("최단거리", KNRoutePriority.KNRoutePriority_Distance, 0),
        Triple("큰길 우선", KNRoutePriority.KNRoutePriority_WideWay, 0),
    )

    // 경로 선택 중 차량 지도에 그릴 경로 (안내 시작·취소하면 null)
    var preview: List<KNRoute>? = null
        set(v) { field = v; listeners.forEach { it() } }

    val active get() = guidance != null

    fun init(app: Application, done: (KNError?) -> Unit) {
        this.app = app
        HudService.openLog(dumpDir(app)) // 차에서 바로 시작해 HUD 서비스가 없어도 로그 파일에
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

    private fun here(): Pair<Int, Int>? = state?.pos?.let { it.x.toInt() to it.y.toInt() }
        ?: KNSDK.sharedGpsManager()?.lastValidGpsData?.pos?.let { it.x.toInt() to it.y.toInt() }

    // 현재 위치 → to 를 종류별로 따로 탐색해 (동시에) 비교 목록으로. 결과가 같은 경로는 이름을 합쳐 한 줄로
    fun plan(to: Place, done: (List<RouteChoice>, String?) -> Unit) {
        val (hx, hy) = here() ?: return done(emptyList(), "현재 위치 없음 (GPS 대기)")
        log("카카오 경로 선택 탐색 → ${to.name}")
        val got = arrayOfNulls<RouteChoice>(CHOICES.size)
        var left = CHOICES.size
        var lastErr: KNError? = null
        fun finish() {
            if (--left > 0) return
            val list = got.filterNotNull()
                .groupBy { it.route.totalDist to it.route.totalTime }.values
                .map { same -> same.first().let { RouteChoice(same.joinToString(" · ") { c -> c.label }, it.priority, it.avoid, it.trip, it.route, to) } }
            log("카카오 경로 선택: " + list.joinToString { "${it.label} ${it.route.totalDist}m ${it.route.totalTime}s ${it.route.totalCost}원" })
            done(list, if (list.isEmpty()) fail("경로 탐색", lastErr) else null)
        }
        for ((i, c) in CHOICES.withIndex()) {
            val (label, pri, av) = c
            KNSDK.makeTripWithStart(KNPOI("현위치", hx, hy, null), KNPOI(to.name, to.x, to.y, null), null, null) { err, trip ->
                if (err != null || trip == null) {
                    main.post { lastErr = err; finish() }
                    return@makeTripWithStart
                }
                trip.routeWithPriority(pri, av) { err2, routes ->
                    main.post {
                        val r = routes?.firstOrNull()
                        if (err2 != null || r == null) lastErr = err2 else got[i] = RouteChoice(label, pri, av, trip, r, to)
                        finish()
                    }
                }
            }
        }
    }

    // 경로 선택에서 고른 것으로 안내 시작 (다시 탐색하지 않음)
    fun go(c: RouteChoice, simul: Boolean) {
        lastTo = c.to
        lastVia = null
        lastSimul = simul
        priority = c.priority
        avoid = c.avoid
        preview = null
        log("카카오 경로 선택: ${c.label}")
        begin(c.trip, c.to, simul)
    }

    // 현재 위치 → (via 거쳐) to 경로 탐색 후 안내 시작 (지금 고른 경로 종류로). view가 있으면 그 화면에 붙인다
    fun start(to: Place, simul: Boolean, via: Place? = null, done: (String?) -> Unit = {}) {
        // 안내 중이면 경로 위 지금 자리에서 (모의주행 재탐색도 그 자리에서 이어짐)
        val (hx, hy) = here() ?: return done("현재 위치 없음 (GPS 대기)")
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
            trip.routeWithPriority(priority, avoid) { err2, _ ->
                main.post {
                    if (err2 != null) return@post done(fail("경로 탐색", err2))
                    begin(trip, to, simul)
                    done(null)
                }
            }
        }
    }

    private fun begin(trip: KNTrip, to: Place, simul: Boolean) {
        val g = attach(simul) ?: return
        destination = to.name
        destAddress = to.address
        val v = view
        if (v != null) v.initWithGuidance(g, trip, priority, avoid) else g.startWithTrip(trip, priority, avoid)
        log("카카오 안내 시작${if (simul) " (모의주행)" else ""}")
    }

    // 목적지 없이 주행: 카메라·구간단속·속도만 HUD·차량 화면으로 (지도엔 교통 정보)
    fun safeDrive() {
        lastTo = null
        lastVia = null
        preview = null
        val g = attach(false) ?: return
        safeMode = true
        safeLogged = false
        destination = "안전운행"
        destAddress = ""
        val v = view
        if (v != null) {
            v.trafficMode = true
            v.initWithGuidance(g, null, priority, avoid)
        } else (g as? KNDriveGuidance)?.startWithoutTrip()
        log("카카오 안전운행 시작")
        listeners.forEach { it() }
    }

    // 지금 안내를 멈추고 새 안내에 콜백을 붙임
    private fun attach(simul: Boolean): KNGuidance? {
        // 재탐색이면 앞 안내는 조용히 멈춤: end()로 끝을 알리면 AA엔 navigationEnded→Started가 붙어 가고 HUD도 한 번 꺼짐 (DHU: 새 안내까지 꺼짐)
        guidance?.let { runCatching { it.stop() } }
        safeMode = false
        val g: KNGuidance = (if (simul) KNSDK.sharedSimulGuidance() else KNSDK.sharedGuidance()) ?: return null
        g.guideStateDelegate = this
        g.locationGuideDelegate = this
        g.routeGuideDelegate = this
        g.safetyGuideDelegate = this
        g.voiceGuideDelegate = this
        g.citsGuideDelegate = this
        g.useBackgroundUpdate = true // 폰 화면이 꺼져도 (AA 연결 중) 계속
        guidance = g
        applyFreq()
        return g
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
        log(if (safeMode) "카카오 안전운행 끝" else "카카오 안내 끝")
        guidance = null
        safeMode = false
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
        val gps = loc.gpsMatched
        val here = loc.location
        val dir = g.routeGuide?.curDirection
        val next = g.routeGuide?.nextDirection
        val trip = g.trip
        if (!safeMode && (here == null || dir == null || trip == null)) return
        // 안전운행엔 경로 위 위치가 없을 수 있음 → GPS에서 직선거리 (KATEC = 미터)
        fun dist(l: KNLocation) = here?.distToLocation(l)
            ?: Math.hypot(l.pos.x.toDouble() - gps.pos.x, l.pos.y.toDouble() - gps.pos.y).toInt()
        val safeties = g.safetyGuide?.safetiesOnGuide.orEmpty()
        if (safeMode && !safeLogged) {
            safeLogged = true
            log("카카오 안전운행: 경로 위치 ${here != null}, 앞쪽 안전 정보 ${safeties.size}개, 주변 ${g.aroundSafeties?.size}개")
        }
        val safety = safeties.firstOrNull { !it.passed && KakaoNaviMapping.sdiCode(it.code.name) != null }
        val lane = g.routeGuide?.lane
        // 구간단속 중: 아직 안 지난 구간 끝(또는 구간 중) 카메라 중 평균 속도가 잡힌 것 (= 시작 카메라를 지남)
        val section = safeties.filterIsInstance<KNSafety_Section>()
            .firstOrNull { !it.passed && KakaoNaviMapping.sdiCode(it.code.name) in setOf(3, 4) && it.sectionAvrSpeed > 0 }
            ?.let {
                KakaoNaviMapping.Section(it.sectionAvrSpeed, dist(it.location).coerceAtLeast(0), it.speedLimit)
            }
        if ((section != null) != inSection) log(if (section != null) "카카오 구간단속 시작: 제한 ${section.speedLimit} 남은 ${section.remain}m" else "카카오 구간단속 끝")
        inSection = section != null
        val guide = KakaoNaviMapping.Guide(
            rg = dir?.rgCode?.value ?: 0,
            turnDistance = dir?.let { dist(it.location) } ?: 0,
            name = dir?.let { it.directionNames?.firstOrNull() ?: it.nodeName.orEmpty() }.orEmpty(),
            nextRg = next?.rgCode?.value,
            nextDistance = next?.let { dist(it.location) } ?: 0,
            remainDistance = trip?.remainDist() ?: 0,
            remainSeconds = trip?.remainTime() ?: 0,
            speed = gps.speed.coerceAtLeast(0), // 모르면 -1이 옴 (모의주행 시작 직후 등)
            safety = safety?.let {
                KakaoNaviMapping.Safety(
                    it.code.name,
                    dist(it.location),
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
            laneDistance = lane?.let { dist(it.location) } ?: 0,
            section = section,
            laneColor = KakaoNaviMapping.laneColor(dir?.laneColor?.name),
        )
        if (guide.laneColor != lastLaneColor) guide.laneColor?.let { log("카카오 유도선: $it → ${guide.name}") }
        lastLaneColor = guide.laneColor
        val tbt = if (safeMode) KakaoNaviMapping.safeTbt(guide) else KakaoNaviMapping.toTbt(guide)
        send(tbt)
        val event = g.routeGuide?.roadEvents.orEmpty()
            .map { it to dist(it.location) }
            .filter { it.second in 0..EVENT_RANGE }
            .minByOrNull { it.second }
            ?.let { (e, d) -> RoadEvent(e.title.orEmpty().ifEmpty { e.desc.orEmpty() }, d, e.icon) }
        if (event != null && event.title != lastEvent) log("카카오 돌발: ${event.title} ${event.distance}m")
        lastEvent = event?.title
        val highway = here?.let { highway(g, it) }.orEmpty()
        val hwLog = highway.joinToString { "${it.kind} ${it.name}" }
        if (hwLog != lastHighway && hwLog.isNotEmpty()) log("카카오 고속도로: $hwLog")
        lastHighway = hwLog
        // 지도엔 GPS가 아니라 경로 위 위치 — 그래야 내 차가 경로선에 딱 붙고 지나온 경로 지우기와 맞는다
        val at = here?.pos?.let { FloatPoint(it.x.toFloat(), it.y.toFloat()) } ?: FloatPoint(gps.pos.x.toFloat(), gps.pos.y.toFloat())
        state = State(
            tbt, guide.rg, next?.rgCode?.value, destination,
            at, gps.angle.toFloat(), here, if (safeMode) null else g.routesOnGuide, event, highway,
        )
        listeners.forEach { it() }
    }

    // 앞쪽 고속도로 시설 가까운 3곳. 휴게소는 SDK가 따로 주는 목록(nearSas)도 합침
    private fun highway(g: KNGuidance, here: KNLocation): List<Highway> {
        val hw = g.routeGuide?.hwInfo ?: return emptyList()
        fun ahead(list: List<KNHighwayRG>) = list.map { it to here.distToLocation(it.location) }.filter { it.second > 0 }
        val sas = ahead(hw.nearSas.orEmpty()).map { it.second }.sorted()
        return ahead(hw.nearRgs.orEmpty() + hw.nearSas.orEmpty() + listOfNotNull(hw.nearestJc))
            .distinctBy { (rg, _) -> rg.highwayRGType() to rg.nodeName }
            .sortedBy { it.second }
            .take(HIGHWAY_COUNT)
            .map { (rg, d) ->
                val kind = when (rg.highwayRGType()) {
                    KNHighwayRGType.KNHighwayRGType_IC -> "IC"
                    KNHighwayRGType.KNHighwayRGType_JC -> "JC"
                    KNHighwayRGType.KNHighwayRGType_TG -> "TG"
                    KNHighwayRGType.KNHighwayRGType_SA -> "SA"
                    KNHighwayRGType.KNHighwayRGType_RA -> "RA"
                }
                val note = when (rg) {
                    is KNHighwayRG_SA -> sas.firstOrNull { it > d }?.let { "다음 휴게소 ${(it - d + 500) / 1000}km" }
                    is KNHighwayRG_TG -> rg.tollFare.takeIf { it > 0 }?.let { "요금 %,d원".format(it) }
                    else -> null
                }
                Highway(kind, rg.nodeName.orEmpty(), d, note)
            }
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
        v.setVolume(volume) // 폰 안내 화면이 여기서 자기 음량(기본 최대)으로 덮어씀 → 옵션 값으로 다시
        // 차가 라디오 같은 자체 소스면 포커스를 요청해야 AA가 내비 음성을 섞어 냄 (없으면 무음, 0.1.5 실차).
        // '음악 줄이기 끔'은 폰 음악이 나올 때만 따름 (SDK는 포커스를 따로 안 잡고 이 콜백 직후 재생)
        if (duck || audio()?.isMusicActive != true) audio()?.requestAudioFocus(focus)
    }

    override fun didFinishPlayVoiceGuide(g: KNGuidance, v: KNGuide_Voice) {
        view?.didFinishPlayVoiceGuide(g, v)
        audio()?.abandonAudioFocusRequest(focus)
    }
    override fun didUpdateCitsGuide(g: KNGuidance, c: KNGuide_Cits) { view?.didUpdateCitsGuide(g, c) }
}
