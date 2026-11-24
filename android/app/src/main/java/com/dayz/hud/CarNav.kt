package com.dayz.hud

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.text.SpannableString
import android.text.Spanned
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.ColorSpace
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.os.HandlerThread
import android.view.Surface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.AppManager
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.DurationSpan
import androidx.car.app.model.CarIcon
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Distance
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Destination
import androidx.car.app.navigation.model.Lane
import androidx.car.app.navigation.model.LaneDirection
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.util.DoublePoint
import com.kakaomobility.knsdk.common.util.FloatPoint
import com.kakaomobility.knsdk.guidance.knguidance.common.KNLocation
import com.kakaomobility.knsdk.map.knmaprenderer.objects.KNMapCameraUpdate
import com.kakaomobility.knsdk.map.knmaprenderer.objects.KNMapCoordinateRegion
import com.kakaomobility.knsdk.map.knmaprenderer.renderer.idl.KNMapControlManager
import com.kakaomobility.knsdk.map.knmapview.constants.KNLineDashType
import com.kakaomobility.knsdk.map.uicustomsupport.renewal.KNMapPolyline
import com.kakaomobility.knsdk.map.uicustomsupport.renewal.theme.base.KNMapTheme
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import java.util.TimeZone
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

// Android Auto 내비 앱 (PLAN.md A1). 폰에서 시작한 카카오 안내(KakaoGuidance)를 차량 화면에 보여 준다.
// - 회전 카드·도착 정보: NavigationTemplate (차량 계기판·순정 HUD에도 updateTrip으로 같이 감)
// - 지도 자리: 카카오 지도 (KNSDK.makeRendererWithFrame → AA Surface)
// Play 미배포 → 폰 AA 개발자 설정 "알 수 없는 출처" 필요
class HudCarAppService : CarAppService() {
    // ponytail: 모든 호스트 허용 (사이드로드 개인용). 배포하면 서명된 호스트 목록으로
    override fun createHostValidator() = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession() = object : Session() {
        init {
            // 차량 연결이 끊기면 안내도 끝냄 — 도착 뒤 안내가 안 끝나고 남으면 SDK가 계속 돌 수 있음 (11/18 밤 데이터 5.8GB 의심)
            lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    if (!KakaoGuidance.active) return
                    HudService.log("차량 연결 끊김 → 안내 종료")
                    KakaoGuidance.stop()
                }
            })
        }

        override fun onCreateScreen(intent: Intent): Screen = NavScreen(carContext)
    }
}

class NavScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {

    private val nav = ctx.getCarService(NavigationManager::class.java)
    private var navigating = false
    @Volatile private var visible: Rect? = null
    private val onState: () -> Unit = { invalidate(); updateTrip(); follow() }

    init {
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() { // 차에서 다른 내비를 켜면
                HudService.log("차량: 안내 중지 요청 받음")
                KakaoGuidance.stop()
            }
        })
        ctx.getCarService(AppManager::class.java).setSurfaceCallback(this)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { KakaoGuidance.listeners += onState; updateTrip() }
            override fun onDestroy(owner: LifecycleOwner) {
                KakaoGuidance.listeners -= onState
                if (navigating) nav.navigationEnded()
                navigating = false
            }
        })
    }

    private var simul = false // DHU 시험용

    override fun onGetTemplate(): Template {
        if (KakaoGuidance.safeMode) {
            val strip = ActionStrip.Builder()
                .addAction(Action.Builder().setTitle("옵션").setOnClickListener { screenManager.push(OptionsScreen(carContext)) }.build())
                .addAction(Action.Builder().setTitle("안전운행 종료").setOnClickListener { KakaoGuidance.stop() }.build())
            return NavigationTemplate.Builder().setActionStrip(strip.build())
                .setMapActionStrip(mapButtons()).setPanModeListener { }
                .setNavigationInfo(MessageInfo.Builder("안전운행").setText("과속 카메라·구간단속·교통 정보").build())
                .build()
        }
        val s = KakaoGuidance.state ?: return destinations()
        // 지도 쪽 PAN 버튼이 있어야 지도가 터치를 받고, 그래야 숨은 오른쪽 위 버튼이 터치로 다시 나온다
        // PAN 버튼은 setPanModeListener가 있어야 보이고, 이동 모드에서만 onScroll이 온다 (나갈 땐 그 자리 유지, ◎로 복귀)
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("옵션").setOnClickListener { screenManager.push(OptionsScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("재탐색").setOnClickListener {
                KakaoGuidance.reroute { e -> if (e != null) CarToast.makeText(carContext, e, CarToast.LENGTH_LONG).show() }
            }.build())
            .addAction(Action.Builder().setTitle("안내 종료").setOnClickListener { KakaoGuidance.stop() }.build())
        val info = RoutingInfo.Builder().setCurrentStep(step(s.rg, s.tbt.turnName, s.tbt), distance(s.tbt.turnDistance))
        s.nextRg?.let { info.setNextStep(step(it, "")) }
        return NavigationTemplate.Builder().setActionStrip(strip.build())
            .setMapActionStrip(mapButtons()).setPanModeListener { }
            .setNavigationInfo(info.build()).setDestinationTravelEstimate(estimate(s.tbt))
            .build()
    }

    // 안내 전: 지도 위에 목적지 목록을 바로 띄운다 (새 AA 화면은 NavigationTemplate 오른쪽 위 버튼을 숨겨서 '목적지' 버튼을 못 누름)
    // 이 템플릿은 거리 표시가 없는 줄은 browsable이어야 한다 (아니면 IllegalArgumentException)
    private fun destinations(): Template {
        val prefs = carContext.getSharedPreferences("hud", Context.MODE_PRIVATE)
        val list = ItemList.Builder()
            .addItem(Row.Builder().setTitle("목적지 검색").setBrowsable(true).setOnClickListener { screenManager.push(SearchScreen(carContext) { simul }) }.build())
        val limit = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        // 즐겨찾기(폰 검색 화면에서 ★로 고정)가 최근 목적지보다 위
        val fav = Favorites.load(prefs)
        for (p in Favorites.withRecents(fav, Recents.load(prefs)).take(limit - 2)) list.addItem(
            Row.Builder().setTitle(if (fav.any(p::same)) "★ ${p.name}" else p.name).addText(p.address.ifEmpty { " " }).setBrowsable(true)
                .setOnClickListener { chooseFromCar(carContext, screenManager, p, simul) }.build(),
        )
        // 시험용이라 맨 아래
        list.addItem(Row.Builder().setTitle(if (simul) "모의주행: 켜짐" else "모의주행: 꺼짐").addText("PC 시험용 (DHU)").setBrowsable(true)
            .setOnClickListener { simul = !simul; invalidate() }.build())
        // 위쪽 버튼: 옵션, 그 옆에 안전운행 (목적지 없이 카메라·구간단속·교통 정보)
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("옵션").setOnClickListener { screenManager.push(OptionsScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("안전운행").setOnClickListener { safeFromCar(carContext) }.build())
        return PlaceListNavigationTemplate.Builder().setTitle("DayzHUD 카카오 길안내").setItemList(list.build())
            .setActionStrip(strip.build()).setMapActionStrip(mapButtons()).setPanModeListener { }.build()
    }

    // 지도 버튼 (AA 규칙: 최대 4개, 아이콘만). PAN이 있어야 지도 끌기·핀치가 들어온다
    private fun mapButtons() = ActionStrip.Builder()
        .addAction(Action.PAN)
        .addAction(Action.Builder().setIcon(plusIcon).setOnClickListener { zoomBy(1 / 1.5f) }.build())
        .addAction(Action.Builder().setIcon(minusIcon).setOnClickListener { zoomBy(1 / 0.667f) }.build())
        .addAction(Action.Builder().setIcon(hereIcon).setOnClickListener { free = null; zoom = baseZoom; camZoom = null; settled = false }.build())
        .build()

    private fun glyph(draw: Canvas.(Paint) -> Unit) = CarIcon.Builder(IconCompat.createWithBitmap(
        Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also {
            Canvas(it).draw(Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 9f; strokeCap = Paint.Cap.ROUND })
        },
    )).build()
    private val plusIcon by lazy { glyph { drawLine(24f, 48f, 72f, 48f, it); drawLine(48f, 24f, 48f, 72f, it) } }
    private val minusIcon by lazy { glyph { drawLine(24f, 48f, 72f, 48f, it) } }
    private val hereIcon by lazy {
        glyph {
            drawCircle(48f, 48f, 24f, it)
            drawCircle(48f, 48f, 7f, Paint(it).apply { style = Paint.Style.FILL })
            drawLine(48f, 8f, 48f, 22f, it)
            drawLine(48f, 74f, 48f, 88f, it)
            drawLine(8f, 48f, 22f, 48f, it)
            drawLine(74f, 48f, 88f, 48f, it)
        }
    }

    // 지도 확대·축소: KNSDK 축척 (작을수록 확대). 범위·배율은 KNSDK 지도 화면의 zoomIn/zoomOut과 같게
    private fun zoomBy(k: Float) {
        val m = map ?: return
        zoom = ((zoom ?: baseZoom ?: m.zoom) * k).coerceIn(0.5f, 2000f)
        camZoom = camZoom?.times(k) // 버튼·핀치는 바로 (속도 변화만 천천히)
        settled = false
    }

    // 차량 계기판·순정 HUD로 가는 안내 (AA가 차에 전달)
    private fun updateTrip() {
        val s = KakaoGuidance.state
        if (s == null || KakaoGuidance.safeMode) {
            if (navigating) nav.navigationEnded()
            navigating = false
            return
        }
        if (!navigating) nav.navigationStarted()
        navigating = true
        val t = s.tbt
        // 다음 회전까지 시간은 SDK가 안 줌 → 남은 시간을 거리 비율로 나눠 추정
        val stepSec = if (t.totalDistance > 0) t.totalTime * t.turnDistance / t.totalDistance else 0L
        nav.updateTrip(
            Trip.Builder()
                .addStep(step(s.rg, t.turnName, t), estimate(t.turnDistance, stepSec))
                .addDestination(Destination.Builder().setName(s.destination).build(), estimate(t))
                .setCurrentRoad(s.tbt.turnName)
                .build(),
        )
    }

    // lanes = 차로 정보가 있으면 회전 카드 아래(와 계기판)에 차로 그림
    private fun step(rg: Int, cue: String, lanes: Tbt? = null): Step {
        val (type, angle) = CarManeuver.of(rg)
        val m = Maneuver.Builder(type).setIcon(CarIcon.Builder(IconCompat.createWithBitmap(arrowBitmap(type, angle))).build())
        if (angle != null) m.setRoundaboutExitAngle(angle)
        val b = Step.Builder(cue.ifEmpty { " " }).setManeuver(m.build())
        if (lanes != null && lanes.lane && lanes.laneTurnInfo.isNotEmpty()) {
            lanes.laneTurnInfo.forEachIndexed { i, bits ->
                val ok = lanes.laneAvailable.getOrElse(i) { 0 }
                val lane = Lane.Builder()
                val dirs = CarManeuver.laneDirs(bits)
                if (dirs.isEmpty()) lane.addDirection(LaneDirection.create(LaneDirection.SHAPE_UNKNOWN, false))
                for (d in dirs) lane.addDirection(LaneDirection.create(d.shape, ok and d.bit != 0))
                b.addLane(lane.build())
            }
            b.setLanesImage(CarIcon.Builder(IconCompat.createWithBitmap(lanesBitmap(lanes))).build())
        }
        return b.build()
    }

    // 차로 그림: 차로마다 칸 하나, 갈 수 있는 방향 화살표를 겹쳐 그림. 안내 차로의 안내 방향은 흰색, 나머지 회색
    private fun lanesBitmap(t: Tbt): Bitmap {
        val cell = 64
        return Bitmap.createBitmap(cell * t.laneTurnInfo.size, cell, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            val sep = Paint().apply { color = 0x66FFFFFF; strokeWidth = 2f }
            t.laneTurnInfo.forEachIndexed { i, bits ->
                val ok = t.laneAvailable.getOrElse(i) { 0 }
                val r = RectF(i * cell + 10f, 8f, (i + 1) * cell - 10f, cell - 4f)
                // 회색 먼저, 흰색을 위에
                for (white in listOf(false, true)) for (d in CarManeuver.laneDirs(bits)) {
                    if ((ok and d.bit != 0) == white) drawArrow(c, r, d.arrow, null, if (white) Color.WHITE else 0xFF6E7380.toInt())
                }
                if (i > 0) c.drawLine(i * cell.toFloat(), 6f, i * cell.toFloat(), cell - 6f, sep)
            }
        }
    }

    private fun estimate(t: Tbt) = estimate(t.totalDistance, t.totalTime)

    private fun estimate(meters: Int, seconds: Long) = TravelEstimate.Builder(
        distance(meters),
        DateTimeWithZone.create(System.currentTimeMillis() + seconds * 1000, TimeZone.getDefault()),
    ).setRemainingTimeSeconds(seconds).build()

    private fun distance(m: Int) =
        if (m < 1000) Distance.create(m.toDouble(), Distance.UNIT_METERS)
        else Distance.create(m / 1000.0, Distance.UNIT_KILOMETERS_P1)

    // ── 지도 자리: 카카오 지도 (KNSDK 렌더러를 AA Surface에 직접) ──

    private var map: KNMapControlManager? = null
    private var trafficInit = false to false // 지도 기본 (traffic, visibleTraffic)
    private var trafficShown: Boolean? = null
    private var shownRoutes: List<KNRoute>? = null

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        val s = c.surface ?: return
        HudService.log("차량 화면 ${c.width}x${c.height}")
        if (!CarCompositor.usable(s)) return HudService.log("차량 화면 못 씀: 이전 앱이 아직 붙어 있음 (재설치 직후) → 폰에서 Android Auto 연결을 다시 하면 됨")
        surfaceSize = c.width to c.height
        KakaoGuidance.init(carContext.applicationContext as Application) { err ->
            if (err != null) return@init HudService.log("차량 지도: 카카오 초기화 실패 ${err.code}")
            map?.onDestroy()
            val reader = compose.start(s, c.width, c.height)
            map = runCatching {
                KNSDK.makeRendererWithFrame(carContext, KNMapTheme.driveNight(), c.width.toFloat(), c.height.toFloat(), reader.surface, false)
            }.onFailure { HudService.log("차량 지도 실패: $it") }.getOrNull()?.apply {
                HudService.log("차량 지도 시작 zoom=$zoom tilt=$tilt 경로 굵기=${routeProperties?.theme?.lineWidth}/${routeProperties?.theme?.strokeWidth}")
                baseZoom = zoom
                camZoom = null
                trafficInit = getTraffic() to isVisibleTraffic
                trafficShown = null
            }
            carRot = null
            cardKey = ""
            shownRoutes = null
            passedLine = null // 새 렌더러: 지나온 선 다시 그림 (재탐색 아님)
            tailLine = null
            lastLoc = null
            drawn = null
            path = emptyList()
            lastFollowAt = 0L
            heading = null
            follow()
            frames.removeCallbacks(frame)
            frames.post(frame)
        }
    }

    override fun onSurfaceDestroyed(c: SurfaceContainer) {
        frames.removeCallbacks(frame)
        map?.onDestroy()
        map = null
        compose.stop()
    }

    // 지도 그림 위에 내 차(카메라가 늘 anchor 자리에 맞춤 → 그 화면 좌표)와 정보판(보이는 영역 오른쪽 위)을 얹어 차량 화면으로
    private val compose = CarCompositor { canvas ->
        // 주유소 가격 표지 (내 차·아래 글보다 아래에 깔리게 먼저). 끝이 주유소 자리를 가리킴
        for ((x, y, b) in stationPx) canvas.drawBitmap(b, x - b.width / 2f, y - b.height, carPaint)
        carRot?.let { r ->
            val a = anchor()
            val (x, y) = carPx ?: (a.x * surfaceSize.first to a.y * surfaceSize.second)
            canvas.save()
            canvas.translate(x, y)
            canvas.rotate(r)
            canvas.drawBitmap(carBmp, -CAR_SIZE / 2f, -CAR_SIZE / 2f, carPaint)
            canvas.restore()
            // 내 차 바로 아래: 현위치 주소 → 목적지 이름 → 목적지 주소 번갈아.
            // 아래에 자리가 없으면 (화면 분할 때 아래 시간 카드) 내 차 위에
            placeLabel()?.let { b ->
                val bottom = (visible ?: Rect(0, 0, surfaceSize.first, surfaceSize.second)).bottom
                val below = y + CAR_SIZE / 2f + 6f
                val top = if (below + b.height <= bottom) below else y - CAR_SIZE / 2f - 6f - b.height
                canvas.drawBitmap(b, x - b.width / 2f, top, carPaint)
            }
        }
        val bar = trafficBar
        if (bar != null) drawTrafficBar(canvas, bar)
        val barSpace = if (bar != null) BAR_DOT + BAR_W / 2 + PANEL_MARGIN else 0f // 막대 + 동그라미만큼 안쪽 여백
        panelOverlay?.let { b ->
            // 보이는 영역(카드·버튼에 안 가리는 지도)의 오른쪽 위. 왼쪽 아래는 실차에서 시간 카드·내 차와 겹쳤음
            // 좁거나 낮은 화면이면 줄이기만 함: 폭은 오른쪽 절반, 높이는 내 차(3/4 지점)에 안 닿게 60%까지
            val v = visible ?: Rect(0, 0, surfaceSize.first, surfaceSize.second)
            // 교통량 막대가 있으면 그 왼쪽으로
            val s = minOf(1f, (v.width() / 2f - PANEL_MARGIN - barSpace) / b.width, v.height() * 0.6f / b.height).coerceAtLeast(0.5f)
            canvas.save()
            canvas.translate(v.right - PANEL_MARGIN - barSpace - b.width * s, v.top + PANEL_MARGIN)
            canvas.scale(s, s)
            canvas.drawBitmap(b, 0f, 0f, carPaint)
            canvas.restore()
        }
        hwOverlay?.let { b ->
            // 보이는 영역의 왼쪽 위 (오른쪽은 정보판). 줄이는 규칙은 정보판과 같음
            val v = visible ?: Rect(0, 0, surfaceSize.first, surfaceSize.second)
            val s = minOf(1f, (v.width() / 2f - PANEL_MARGIN * 2) / b.width, v.height() * 0.6f / b.height).coerceAtLeast(0.5f)
            canvas.save()
            canvas.translate(v.left + PANEL_MARGIN, v.top + PANEL_MARGIN)
            canvas.scale(s, s)
            canvas.drawBitmap(b, 0f, 0f, carPaint)
            canvas.restore()
        }
    }

    // 지도 끌기: 내 차 따라가기를 멈추고 그 자리 보기 (현재 위치 버튼으로 복귀)
    override fun onScroll(distanceX: Float, distanceY: Float) {
        frames.post {
            val m = map ?: return@post
            val c = m.katecToScreen(free ?: drawn ?: return@post)
            free = m.screenToKatec(FloatPoint(c.x + distanceX, c.y + distanceY))
            settled = false
        }
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (scaleFactor > 0f) frames.post { zoomBy(1 / scaleFactor) }
    }

    // 위쪽 버튼(옵션·재탐색·안내 종료)과 지도 버튼이 나왔다 숨으면 보이는 영역의 위·오른쪽만 줄었다 늘었다 함
    // → 정보판·교통량 막대·내 차가 따라 움직이지 않게 넓은 쪽을 유지 (버튼은 그 위에 겹쳐 보임).
    // 왼쪽·아래가 바뀌면 (화면 바뀜·화면 분할) 새 영역으로
    override fun onVisibleAreaChanged(r: Rect) {
        val v = visible
        val next = if (v != null && v.left == r.left && v.bottom == r.bottom) Rect(r).apply { union(v) } else r
        if (next != v) HudService.log("차량 화면 보이는 영역 $next (받은 값 $r)")
        visible = next
    }

    // 내 차를 카드에 안 가리는 영역의 가운데·아래쪽 3/4 지점에 (화면 비율 0~1, SDK 화면도 같은 방식)
    private fun anchor(): FloatPoint {
        val v = visible ?: return FloatPoint(0.5f, 0.75f)
        val w = surfaceSize.first.coerceAtLeast(1).toFloat()
        val h = surfaceSize.second.coerceAtLeast(1).toFloat()
        return FloatPoint(v.exactCenterX() / w, (v.top + v.height() * 0.75f) / h)
    }

    private var surfaceSize = 0 to 0
    private val CAR_SIZE = 80
    private val FRAME_MS = 33L // 30fps
    private val ANIM_MS = 1_000L // 첫 구간 (그다음은 실제 위치 간격으로)
    // ponytail: 지도 회전 부드러움은 감으로 정함 — 길이 비스듬히 오래 남으면 줄이고, 지도가 출렁이면 늘림
    private val CAM_TAU = 600f // ms. 지도 방향이 길 방향을 따라가는 시간 상수 (꼭짓점마다 꺾이는 걸 부드럽게)
    private val ARROW_TAU = 150f // ms. 내 차 화살표
    private val PANEL_MARGIN = 12f
    private val PANEL_W = 270 // 정보판 폭 (글줄 "신호·과속 카메라 137m"이 들어가게)
    private val PASSED_GRAY = 0xFF8A8F98.toInt()
    // ponytail: 경로선 굵기(theme.lineWidth)와 폴리라인 굵기 단위가 달라 배율로 맞춤. DHU 실측: 파란 선(테두리 포함) 15px, 1배 ≈ 4.8px → 3배
    private val PASSED_WIDTH = 3f

    // 흰 동그라미 안에 앰버 화살표 (지도 위 내 차). 가운데가 내 위치
    private val carBmp by lazy { carIcon() }
    private val carPaint = Paint(Paint.FILTER_BITMAP_FLAG) // 돌려 그릴 때 테두리 계단 없게
    private fun carIcon() = Bitmap.createBitmap(CAR_SIZE, CAR_SIZE, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        val r = CAR_SIZE / 2f
        c.drawCircle(r, r, r - 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        c.drawCircle(r, r, r - 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MainActivity.ACCENT; style = Paint.Style.STROKE; strokeWidth = 5f })
        val path = Path().apply { moveTo(r, r - 22f); lineTo(r + 16f, r + 18f); lineTo(r, r + 9f); lineTo(r - 16f, r + 18f); close() }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MainActivity.ACCENT })
    }

    // ── 내 차 움직임: 안내 위치는 1초마다 오므로 그 사이를 경로를 따라 매 프레임 채운다 ──
    // 카메라도 매 프레임 내 차에 맞춤 → 내 차가 화면 같은 자리(anchor)에 머문다. 내 차 아이콘은 그 자리에 합성 (CarCompositor).
    // 끊김 없게: 새 위치가 오면 지금 그리고 있는 자리에서 이어 가고, 구간 시간을 실제 간격보다 조금 길게 잡아 도착해서 서는 일이 없게.
    // 지도 방향: 내 차 자리의 길 방향(경로 꼭짓점으로 계산 → GPS 방향보다 정확)을 부드럽게 따라가 길이 늘 화면 위로 곧게.
    // 지나온 경로: 파란 경로는 그대로 두고 그 위에 회색 선을 덮는다 (SDK cullPassedRoute는 자르는 시점이 마커와 어긋남).
    //  - passed(1초마다) = 지금까지 그린 자리까지 굳힌 길
    //  - tail(매 프레임) = 이번 구간 시작 → 지금 그리는 자리 (점 몇 개라 가벼움)
    private var routePts: List<FloatPoint> = emptyList()
    private var passed: List<FloatPoint> = emptyList()
    private var passedLine: KNMapPolyline? = null
    private var tailLine: KNMapPolyline? = null
    private var path: List<FloatPoint> = emptyList() // 이번 구간에 갈 길 (지금 자리 → 남은 길 → 경로 꼭짓점들 → 새 위치)
    private var drawn: FloatPoint? = null // 마지막으로 그린 내 차 자리
    private var drawnUpto = 0 // 그 앞에 지난 path 꼭짓점 번호
    private var lastLoc: KNLocation? = null
    private var lastFollowAt = 0L
    private var interval = ANIM_MS.toFloat() // 위치 갱신 간격 (평균)
    private var animMs = ANIM_MS
    private var heading: Float? = null // 내 차 자리의 길 방향 (북 0, 시계 방향)
    private var moving = true
    private val MOVING_KMH = 5
    private var carAngle = 0f
    private var camBearing = 0f
    private var lastFrameAt = 0L
    private var zoom: Float? = null // 버튼·핀치로 바꾼 축척 (null = SDK 기본)
    private var baseZoom: Float? = null // 지도 처음 축척 (현재 위치 버튼으로 되돌림)
    private var camZoom: Float? = null // 지금 그리는 축척 = 고른 축척 × 속도 배율, 천천히 따라감
    private val ZOOM_TAU = 2_500f // ms. 속도가 바뀌어 지도가 넓어지고 좁아지는 시간 상수 (출렁임 없게 느리게)
    private var free: FloatPoint? = null // 지도를 끌어 놓은 자리 (null = 내 차 따라감)
    @Volatile private var carRot: Float? = null // 화면에서 내 차 아이콘 기울기 (진행 방향 - 지도 방향)
    @Volatile private var carPx: Pair<Float, Float>? = null // 지도를 끈 동안 내 차 화면 자리
    private var previewAt: FloatPoint? = null // 경로 선택 중 내 차 자리 (그 동안은 frame에서 화면 자리를 잼)
    private var startedAt = 0L
    private var settled = false
    private var ticks = 0
    private val frames = Handler(Looper.getMainLooper())
    private val frame = object : Runnable {
        override fun run() {
            drawFrame()
            // 경로 선택 중: fitTo 카메라는 moveCamera 직후가 아니라 나중에 반영됨 → 반영될 때까지 매 프레임 내 차 화면 자리를 다시 잼
            previewAt?.let { p -> map?.katecToScreen(p)?.let { q -> carPx = q.x to q.y } }
            // 지도가 멈춰 있어도 덧그림이 바뀌면 다시 합성 (움직일 땐 지도 프레임마다 합성됨)
            val key = "${labelText()}|${System.identityHashCode(trafficBar)}|$carPx"
            if (key != overlayKey) {
                overlayKey = key
                if (settled) compose.redraw()
            }
            if (map != null) frames.postDelayed(this, FRAME_MS)
        }
    }

    // 같은 점이 연달아 있으면 (길이 0인 마디) SDK가 선을 통째로 안 그림 → 빼고 그림
    private fun gray(m: KNMapControlManager, all: List<FloatPoint>) =
        all.filterIndexed { i, q -> i == 0 || q.x != all[i - 1].x || q.y != all[i - 1].y }.let { pts -> if (pts.size < 2) null
        // 우선순위 높게: 파란 경로 위에 그려져야 함
        else KNMapPolyline(pts, PASSED_GRAY, (m.routeProperties?.theme?.lineWidth ?: 12f) * PASSED_WIDTH, KNLineDashType.KNLineDashType_Solid, Int.MAX_VALUE - 3)
            .also(m::addPolyline) }

    // 1초마다(안내 위치 갱신): 경로선, 지나온 길 고정 부분, 다음 구간에 갈 길, 지도 회전, 정보 카드 내용
    private fun follow() {
        val m = map ?: return
        val s = KakaoGuidance.state
        val pos = s?.pos ?: KNSDK.sharedGpsManager()?.lastValidGpsData?.pos?.let { FloatPoint(it.x.toFloat(), it.y.toFloat()) } ?: return
        val loc = s?.location
        val here = drawn ?: pos
        updateTraffic(loc.takeIf { s?.routes != null }, s?.tbt?.totalDistance ?: 0)
        updateAddress(pos)
        // 경로 선택 중: 고른 경로 전체가 보이는 영역에 들어오게 (북쪽 위). 내 차 따라가기는 멈춤
        // 안전운행이면 지도에 교통 정보 (끝나면 지도 기본으로)
        val safe = KakaoGuidance.safeMode
        if (safe != trafficShown) {
            trafficShown = safe
            m.setTraffic(safe || trafficInit.first)
            m.isVisibleTraffic = safe || trafficInit.second
            HudService.log("차량 지도 교통 정보 ${m.getTraffic()}/${m.isVisibleTraffic}")
        }
        val pv = KakaoGuidance.preview
        if (pv != null && s == null) {
            if (pv !== shownRoutes) {
                m.setRoutes(pv)
                shownRoutes = pv
                val v = visible ?: Rect(0, 0, surfaceSize.first, surfaceSize.second)
                m.moveCamera(KNMapCameraUpdate().fitTo(KNMapCoordinateRegion().initWithRoute(pv), RectF(v)).bearingTo(0f), false, false)
            }
            // 지도가 경로 전체에 맞춰져 있으니 내 차는 고정 자리(anchor) 말고 지도 위 실제 자리에 (북쪽 위라 화살표는 진행 방향 그대로)
            previewAt = here
            carRot = carAngle
            settled = true
            return
        }
        previewAt = null
        if (s?.routes !== shownRoutes) {
            s?.routes?.let(m::setRoutes) ?: m.removeRoutesAll()
            shownRoutes = s?.routes
            lastLoc = null // 재탐색: 지나온 길은 그대로 두고 새 경로 기준으로 다시
            routePts = loc?.route?.routePolylineWGS84().orEmpty().map {
                val p = KNSDK.convertWGS84ToKATEC(it.getValue("x").toDouble(), it.getValue("y").toDouble())
                FloatPoint(p.x.toFloat(), p.y.toFloat())
            }
        }
        // 정차 중엔 위치가 제자리에서 앞뒤로 흔들려 길 방향이 뒤집힘 → 움직일 때만 방향을 바꿈 (안내 없으면 1초에 5m 이상)
        moving = s?.tbt?.speed?.let { it >= MOVING_KMH } ?: (Math.hypot((pos.x - here.x).toDouble(), (pos.y - here.y).toDouble()) >= 5.0)
        val prev = lastLoc
        // ponytail: polyIdx가 routePolylineWGS84 순번과 같다고 봄 (SDK 내부 점 배열을 그대로 풀어 놓은 것). 어긋나면 가장 가까운 꼭짓점 찾기로
        val ahead = if (prev != null && loc != null && loc.polyIdx >= prev.polyIdx) {
            routePts.subList((prev.polyIdx + 1).coerceAtMost(routePts.size), (loc.polyIdx + 1).coerceIn(0, routePts.size))
        } else emptyList()
        passed = if (s?.routes == null) emptyList() else passed + path.take(drawnUpto + 1) + here
        path = listOf(here) + path.drop(drawnUpto + 1) + ahead + pos
        drawnUpto = 0
        passedLine?.let(m::removePolyline)
        passedLine = gray(m, passed)
        lastLoc = loc
        val now = SystemClock.uptimeMillis()
        // 다음 위치까지 걸릴 시간 ≈ 평균 간격(모의주행은 0.5초). 15% 길게 → 도착 전에 다음 위치가 와서 멈췄다 가는 끊김이 없음 (대신 실제보다 살짝 뒤)
        if (lastFollowAt != 0L) interval = interval * 0.7f + (now - lastFollowAt).coerceAtMost(3_000L) * 0.3f
        animMs = (interval * 1.15f).toLong().coerceIn(300L, 2_000L)
        lastFollowAt = now
        startedAt = now
        settled = false
        updateCard(m, s)
        fuelStations(pos)
        if (++ticks % 10 == 1) HudService.log("차량 지도 zoom=${m.zoom} 구간=${animMs}ms 차 화면=${drawn?.let(m::katecToScreen)?.let { "${it.x},${it.y}" }} anchor=${anchor().let { "${it.x * surfaceSize.first},${it.y * surfaceSize.second}" }} bearing=${m.bearing}/$camBearing 길=$heading 회색=${passed.size}점/${passedLine != null}")
    }

    // 매 프레임: 이번 구간 길을 따라 내 차·회색 꼬리·카메라를 옮김 (다 가고 방향도 다 맞으면 멈춤)
    private fun drawFrame() {
        val m = map ?: return
        if (path.isEmpty() || settled) return
        val now = SystemClock.uptimeMillis()
        val dt = (now - lastFrameAt).coerceIn(0L, 200L).toFloat()
        lastFrameAt = now
        val f = ((now - startedAt) / animMs.toFloat()).coerceIn(0f, 1f)
        val xy = path.map { it.x to it.y }
        val (p, upto) = CarPath.along(xy, f).let { (q, i) -> FloatPoint(q.first, q.second) to i }
        drawn = p
        drawnUpto = upto
        if (KakaoGuidance.state?.routes != null) {
            tailLine?.let(m::removePolyline)
            tailLine = gray(m, path.take(upto + 1) + p)
        } else tailLine?.let(m::removePolyline).also { tailLine = null }
        val h = CarPath.heading(xy, upto)
        if (h != null && heading == null) { camBearing = h; carAngle = h } // 처음엔 바로 맞춤
        if (h != null && moving) heading = h
        val want = heading ?: camBearing
        camBearing = CarPath.lerpAngle(camBearing, want, 1 - exp(-dt / CAM_TAU))
        carAngle = CarPath.lerpAngle(carAngle, want, 1 - exp(-dt / ARROW_TAU))
        // 매 프레임 바로 옮김 (animateCamera(…, 0)은 애니메이션 객체를 매번 만들고 줌이 틀어짐)
        val cam = KNMapCameraUpdate().targetTo(free ?: p).bearingTo(camBearing).anchorTo(anchor())
        // 빠를수록 넓게 봄 → 한 프레임에 지도가 덜 움직여 고속에서 덜 끊겨 보임
        val wantZoom = (zoom ?: baseZoom)?.let { it * CarPath.speedZoom(KakaoGuidance.state?.tbt?.speed ?: 0) }
        if (wantZoom != null) {
            val z = camZoom?.let { CarPath.lerpZoom(it, wantZoom, 1 - exp(-dt / ZOOM_TAU)) } ?: wantZoom
            camZoom = z
            cam.zoomTo(z)
        }
        m.moveCamera(cam, false, false)
        carRot = CarPath.turn(camBearing, carAngle)
        carPx = if (free != null) m.katecToScreen(p).let { it.x to it.y } else null
        // 카메라를 옮긴 이 프레임 기준 화면 자리 (지도 마커는 카메라와 다른 프레임에 반영돼 떨림 → 정보판처럼 합성)
        stationPx = stations.mapNotNull { (st, b) ->
            val q = m.katecToScreen(FloatPoint(st.x.toFloat(), st.y.toFloat()))
            if (q.x < 0 || q.y < 0 || q.x > surfaceSize.first || q.y > surfaceSize.second) null else Triple(q.x, q.y, b)
        }
        settled = f >= 1f && abs(CarPath.turn(camBearing, want)) < 0.3f && abs(CarPath.turn(carAngle, want)) < 0.3f &&
            (wantZoom == null || abs(ln(camZoom!! / wantZoom)) < 0.01f)
    }

    // ── 오른쪽 교통량 막대 (남은 경로, 아래 = 내 자리 · 위 = 목적지) ──
    // 경로 위 지점(KNLocation)마다 교통 상태가 있음 → 남은 거리를 같은 간격으로 짚어 색칠. 5초마다·경로 바뀌면 바로
    @Volatile private var trafficBar: List<Pair<Float, Int>>? = null
    private var trafficAt = 0L
    private var trafficRoute: KNRoute? = null
    private var overlayKey = ""
    private val BAR_W = 8f
    private val BAR_DOT = 11f // 아래 끝 내 자리 동그라미 반지름
    private val TRAFFIC_N = 60

    private fun updateTraffic(loc: KNLocation?, remain: Int) {
        if (loc == null || remain <= 0) {
            trafficBar = null
            trafficRoute = null
            return
        }
        val now = SystemClock.uptimeMillis()
        val newRoute = loc.route !== trafficRoute
        if (!newRoute && now - trafficAt < 5_000) return
        trafficAt = now
        val states = (0 until TRAFFIC_N).map { i ->
            runCatching { loc.locationAfterDist(((i + 0.5f) * remain / TRAFFIC_N).toInt())?.trafficSt?.name }.getOrNull() ?: "Unknown"
        }
        trafficBar = CarPath.trafficRuns(states)
        if (newRoute) {
            trafficRoute = loc.route
            HudService.log("교통량 막대: 남은 ${remain}m ${states.groupingBy { it.removePrefix("KNTrafficState_") }.eachCount()}")
        }
    }

    private fun drawTrafficBar(c: Canvas, runs: List<Pair<Float, Int>>) {
        val v = visible ?: Rect(0, 0, surfaceSize.first, surfaceSize.second)
        val right = v.right - PANEL_MARGIN - (BAR_DOT - BAR_W / 2) // 동그라미가 막대보다 넓음 → 그만큼 안쪽
        val left = right - BAR_W
        val top = v.top + PANEL_MARGIN
        val bottom = v.bottom - PANEL_MARGIN - BAR_DOT // 아래 내 자리 동그라미 자리
        val len = bottom - top
        if (len <= 0f) return
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        // 지도 위에서 보이게 어두운 테두리
        c.drawRoundRect(RectF(left - 3f, top - 3f, right + 3f, bottom + 3f), BAR_W, BAR_W, p.apply { color = 0xCC000000.toInt() })
        var from = 0f
        for ((end, color) in runs) {
            c.drawRect(left, bottom - end * len, right, bottom - from * len, p.apply { this.color = color })
            from = end
        }
        // 위 끝 = 목적지 (흰 막대), 아래 끝 = 내 자리 (내 차 아이콘과 같은 흰 원·앰버 테두리)
        c.drawRect(left - 3f, top - 3f, right + 3f, top + 3f, p.apply { color = Color.WHITE })
        val cx = (left + right) / 2
        c.drawCircle(cx, bottom, BAR_DOT, p.apply { color = Color.WHITE })
        c.drawCircle(cx, bottom, BAR_DOT, p.apply { color = MainActivity.ACCENT; style = Paint.Style.STROKE; strokeWidth = 4f })
    }

    // ── 내 차 아래 글: 현위치 주소 ↔ 목적지 (안내 중일 때만 번갈아, 4초씩) ──
    // 주소는 SDK 역지오코딩 (지역 단위로 받아 캐시). 30m 넘게 움직였을 때 10초마다, 150m 넘게 움직이면 바로
    @Volatile private var address: String? = null
    private var addrAt = 0L
    private var addrPos: FloatPoint? = null
    private var addrLogged = false
    private var labelCache: Pair<String, Bitmap>? = null
    private val LABEL_MS = 4_000L

    private fun updateAddress(pos: FloatPoint) {
        val now = SystemClock.uptimeMillis()
        val moved = addrPos?.let { Math.hypot((pos.x - it.x).toDouble(), (pos.y - it.y).toDouble()) } ?: Double.MAX_VALUE
        if (moved < 30 || (now - addrAt < 10_000 && moved < 150)) return
        addrAt = now
        addrPos = pos
        runCatching {
            KNSDK.reverseGeocodeWithPos(DoublePoint(pos.x.toDouble(), pos.y.toDouble()), true) { err, a, b, c, d ->
                if (!addrLogged) {
                    addrLogged = true
                    HudService.log(if (err != null) "현위치 주소 실패: ${err.code} ${err.msg}" else "현위치 주소: $a | $b | $c | $d")
                }
                if (err == null) address = KakaoNaviMapping.address(listOf(a, b, c, d))
            }
        }.onFailure { if (!addrLogged) HudService.log("현위치 주소 실패: $it").also { addrLogged = true } }
    }

    // 지금 보일 글과 앞 점 색 (현위치 앰버, 목적지 이름·주소 빨강). 안내 없으면 주소만
    private fun labelText(): Pair<String, Int>? {
        val s = KakaoGuidance.state
        val guiding = s != null && s.routes != null
        val items = listOf(address, s?.destination?.takeIf { guiding }, KakaoGuidance.destAddress.takeIf { guiding })
        val i = KakaoNaviMapping.labelTurn(items, SystemClock.uptimeMillis() / LABEL_MS) ?: return null
        return items[i]!! to if (i == 0) MainActivity.ACCENT else 0xFFE53935.toInt()
    }

    private fun placeLabel(): Bitmap? {
        val (text, dot) = labelText() ?: return null
        labelCache?.takeIf { it.first == text }?.let { return it.second }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; color = Color.WHITE }
        val t = fit(text, p, 360f)
        val w = (p.measureText(t) + 52f).toInt()
        val h = 40
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202329.toInt() })
            c.drawCircle(22f, h / 2f, 7f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = dot })
            c.drawText(t, 38f, h / 2f - (p.ascent() + p.descent()) / 2, p)
            labelCache = text to it
        }
    }

    // ── 정보판: 현재 속도, 카메라·주의 지점(제한속도·거리), 돌발 ──
    // AA 템플릿엔 속도·카메라 칸이 없어서 직접 그린다 (바탕 없이 글자·표지만, 글자엔 그림자). 위치는 왼쪽 칸 회전 카드 바로 아래.
    // 지도 마커로 그리면 지도 SDK가 카메라·마커를 다른 프레임에 반영해 떨림 → 지도 그림 위에 화면 좌표로 합성 (CarCompositor)
    @Volatile private var panelOverlay: Bitmap? = null
    @Volatile private var hwOverlay: Bitmap? = null
    private var cardKey = ""

    private fun updateCard(@Suppress("UNUSED_PARAMETER") m: KNMapControlManager, s: KakaoGuidance.State?) {
        if (s == null) {
            panelOverlay = null
            hwOverlay = null
            cardKey = ""
            return
        }
        val t = s.tbt.takeIf { it.safe }
        val ev = s.event
        val hw = s.highway.joinToString { "${it.kind}${it.name}${it.distance / 100}${it.note}" }
        val key = listOf(s.tbt.speed, t?.safeCode, t?.safeSpeedLimit, t?.safeDistance?.div(10), t?.block, t?.blockSpeed, t?.blockDistance?.div(100), ev?.title, ev?.distance?.div(10), hw).joinToString("|")
        if (key == cardKey) return
        panelOverlay = panelBitmap(s.tbt.speed, t, ev)
        hwOverlay = s.highway.takeIf { it.isNotEmpty() }?.let(::highwayBitmap)
        cardKey = key
    }

    // 오른쪽 위에 붙는 정보판, 바탕 없음: 오른쪽 열에 속도 → 그 아래 제한속도 표지 → 글줄(오른쪽 맞춤)
    private fun panelBitmap(speed: Int, t: Tbt?, ev: KakaoGuidance.RoadEvent?): Bitmap {
        val limit = t?.safeSpeedLimit ?: 0
        val lines = buildList {
            // 구간단속 중이면 "구간 단속중"이 대신하므로 "구간단속 중 n km" 줄은 뺌
            if (t != null && !(t.block && t.safeCode == 4)) add(meters(t.safeDistance) to 0xFFFFC107.toInt())
            if (t != null && t.block) {
                add("구간 단속중 · 남은 ${meters(t.blockDistance)}" to 0xFFFFC107.toInt())
                // 평균이 제한속도를 넘으면 빨강
                if (t.blockSpeed > 0) add("평균 ${t.blockSpeed}km/h" to if (limit > 0 && t.blockSpeed > limit) 0xFFFF5252.toInt() else 0xFF69F0AE.toInt())
            }
            if (ev != null) add("${ev.title} ${meters(ev.distance)}" to 0xFFFF8A65.toInt())
        }
        val w = PANEL_W
        val cx = w - 62f // 속도·표지 열 가운데
        val top = if (limit > 0) 206 else 108 // 속도(108) + 표지(98)
        val h = top + lines.size * 36 + (if (lines.isEmpty()) 0 else 8)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            val fill = Paint(Paint.ANTI_ALIAS_FLAG)
            // 바탕이 없으니 지도 위에서도 읽히게 글자에 그림자
            val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true; setShadowLayer(6f, 0f, 2f, Color.BLACK) }
            // 현재 속도 (제한속도 넘으면 빨강)
            mid.color = if (limit > 0 && speed > limit) 0xFFFF5252.toInt() else Color.WHITE
            mid.textSize = 64f
            c.drawText("$speed", cx, 64f, mid)
            mid.color = 0xFFB0B4BC.toInt()
            mid.textSize = 20f
            c.drawText("km/h", cx, 90f, mid)
            // 제한속도 표지 (빨간 테두리 흰 원), 속도 바로 아래
            if (limit > 0) {
                c.drawCircle(cx, 154f, 44f, Paint(fill).apply { color = Color.WHITE })
                c.drawCircle(cx, 154f, 38f, Paint(fill).apply { color = 0xFFE53935.toInt(); style = Paint.Style.STROKE; strokeWidth = 10f })
                mid.color = Color.BLACK
                mid.textSize = 38f
                c.drawText("$limit", cx, 168f, mid)
            }
            // 글줄: 카메라·주의 지점, 구간 평균, 돌발 (오른쪽 끝에 맞춤)
            val right = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f; isFakeBoldText = true; textAlign = Paint.Align.RIGHT; setShadowLayer(6f, 0f, 2f, Color.BLACK) }
            val end = w - 8f
            var y = top + 26f
            for ((i, line) in lines.withIndex()) {
                // 돌발은 SDK 아이콘을 글 앞에
                val icon = if (ev != null && i == lines.lastIndex) ev.icon else null
                right.color = line.second
                val text = fit(line.first, right, end - 8f - (if (icon != null) 36f else 0f))
                c.drawText(text, end, y, right)
                if (icon != null) {
                    val x = end - right.measureText(text) - 36f
                    c.drawBitmap(icon, null, RectF(x, y - 24f, x + 30f, y + 6f), null)
                }
                y += 36f
            }
        }
    }

    // 왼쪽 위 고속도로 시설 카드: 먼 곳이 위, 가장 가까운 곳이 맨 아래 (지나가면 아래 카드부터 바뀜)
    private fun highwayBitmap(hw: List<KakaoGuidance.Highway>): Bitmap {
        val w = PANEL_W
        val h = hw.sumOf { hwCardH(it) + HW_GAP } - HW_GAP
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            var top = 0f
            for (item in hw.asReversed()) {
                hwCard(c, top, w.toFloat(), item)
                top += hwCardH(item) + HW_GAP
            }
        }
    }

    private val HW_GAP = 6
    private fun hwCardH(item: KakaoGuidance.Highway) = if (item.note != null) 96 else 74

    // [동그라미 표지 | 이름 / 거리 / 메모] 어두운 카드 (상표 그림 없이 글자 표지)
    private fun hwCard(c: Canvas, top: Float, w: Float, item: KakaoGuidance.Highway) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawRoundRect(RectF(0f, top, w, top + hwCardH(item)), 12f, 12f, p.apply { color = 0xE6202329.toInt() })
        val (label, color) = when (item.kind) {
            "IC" -> "IC" to 0xFF1E88E5.toInt()
            "JC" -> "JC" to 0xFF1E88E5.toInt()
            "TG" -> "요금" to 0xFF43A047.toInt()
            "SA" -> "휴" to 0xFFFB8C00.toInt()
            else -> "P" to 0xFF1E88E5.toInt() // 졸음쉼터
        }
        c.drawCircle(34f, top + 37f, 22f, p.apply { this.color = color })
        val mid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER; isFakeBoldText = true; this.color = Color.WHITE
            textSize = if (label.length > 1) 17f else 24f
        }
        c.drawText(label, 34f, top + 37f - (mid.ascent() + mid.descent()) / 2, mid)
        val x = 66f
        val name = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; this.color = 0xFFC8CCD2.toInt() }
        c.drawText(fit(item.name, name, w - x - 10f), x, top + 28f, name)
        val dist = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 30f; isFakeBoldText = true; this.color = Color.WHITE }
        c.drawText(meters(item.distance), x, top + 62f, dist)
        item.note?.let {
            val note = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; this.color = 0xFFFFD54F.toInt() }
            c.drawText(fit(it, note, w - x - 10f), x, top + 88f, note)
        }
    }

    // ── 주유소 가격 (오피넷): 옵션에서 유종을 고르고 키가 있을 때만. 반경 3km를 받아 두고 1.5km 움직이거나 10분 지나면 다시 ──
    private var stations: List<Pair<Opinet.Station, Bitmap>> = emptyList()
    @Volatile private var stationPx: List<Triple<Float, Float, Bitmap>> = emptyList()
    private var fetchAt: FloatPoint? = null
    private var fetchFuel = ""
    private var fetchedAt = 0L
    private var fetching = false

    private fun fuelStations(pos: FloatPoint) {
        val prefs = carContext.getSharedPreferences("hud", Context.MODE_PRIVATE)
        val fuel = Opinet.fuel(prefs)
        val key = ApiKeys.opinet(prefs)
        if (fuel.isEmpty() || key.isEmpty()) {
            if (stations.isNotEmpty()) { stations = emptyList(); stationPx = emptyList(); fetchAt = null }
            return
        }
        val at = fetchAt
        val near = at != null && Math.hypot((pos.x - at.x).toDouble(), (pos.y - at.y).toDouble()) < 1_500
        if (fetching || (fuel == fetchFuel && near && SystemClock.uptimeMillis() - fetchedAt < 600_000)) return
        fetching = true
        // 실패해도 자리·시각은 남겨 1.5km/10분 전엔 다시 안 부름 (하루 호출 한도)
        fetchAt = pos
        fetchFuel = fuel
        fetchedAt = SystemClock.uptimeMillis()
        thread {
            val r = runCatching { Opinet.around(key, pos.x.toDouble(), pos.y.toDouble(), fuel) }
            frames.post {
                fetching = false
                r.onFailure { HudService.log("주유소 가격 실패: $it") }.onSuccess { list ->
                    HudService.log("주유소 가격 ${Opinet.fuelName(fuel)} ${list.size}곳")
                    stations = list.map { it to stationBadge(it) }
                    settled = false // 다음 프레임에 화면 자리 계산
                }
            }
        }
    }

    // [브랜드 글자 | 가격] 둥근 표지 + 아래 꼭지 (꼭지 끝 = 주유소 자리)
    private fun stationBadge(st: Opinet.Station): Bitmap {
        val (label, color) = Opinet.badge(st.brand)
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 22f; this.color = Color.WHITE }
        val price = "%,d".format(st.price)
        val lw = txt.measureText(label) + 16f
        val w = (lw + txt.measureText(price) + 16f).toInt()
        val h = 34
        val tip = 8
        return Bitmap.createBitmap(w, h + tip, Bitmap.Config.ARGB_8888).also {
            val c = Canvas(it)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), 10f, 10f, p.apply { this.color = 0xE6202329.toInt() })
            c.drawPath(Path().apply { moveTo(w / 2f - 7, h.toFloat()); lineTo(w / 2f, (h + tip).toFloat()); lineTo(w / 2f + 7, h.toFloat()); close() }, p)
            c.drawRoundRect(RectF(0f, 0f, lw, h.toFloat()), 10f, 10f, p.apply { this.color = color })
            txt.color = if (st.brand == "SOL") Color.BLACK else Color.WHITE // 노란 바탕엔 검은 글자
            c.drawText(label, 8f, 25f, txt)
            txt.color = Color.WHITE
            c.drawText(price, lw + 8f, 25f, txt)
        }
    }

    private fun meters(m: Int) = if (m < 1000) "${m}m" else "%.1fkm".format(m / 1000.0)

    // 칸보다 길면 뒤를 …로
    private fun fit(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var e = s.length
        while (e > 1 && p.measureText(s.substring(0, e) + "…") > max) e--
        return s.substring(0, e) + "…"
    }

    private fun arrowBitmap(type: Int, angle: Int?) = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
        drawArrow(Canvas(it), RectF(8f, 8f, 120f, 120f), type, angle, Color.WHITE)
    }

    // ponytail: 화살표는 방향 각도 하나로 단순화 (유턴·도착·로터리만 따로). 차로 그림도 이걸로
    private fun drawArrow(c: Canvas, r: RectF, type: Int, roundabout: Int?, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; style = Paint.Style.STROKE; strokeWidth = r.width() * 0.12f
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        val cx = r.centerX()
        val mid = r.top + r.height() * 0.45f
        when {
            type == Maneuver.TYPE_DESTINATION || type == Maneuver.TYPE_DESTINATION_STRAIGHT -> {
                c.drawCircle(cx, r.centerY(), r.width() * 0.3f, p)
                c.drawCircle(cx, r.centerY(), r.width() * 0.08f, p.apply { style = Paint.Style.FILL })
            }
            type == Maneuver.TYPE_U_TURN_LEFT -> {
                val w = r.width() * 0.22f
                val path = Path().apply {
                    moveTo(cx + w, r.bottom); lineTo(cx + w, mid)
                    arcTo(RectF(cx - w, mid - w, cx + w, mid + w), 0f, -180f); lineTo(cx - w, r.bottom - r.height() * 0.2f)
                }
                c.drawPath(path, p)
                head(c, cx - w, r.bottom - r.height() * 0.15f, 180.0, r.width(), p)
            }
            roundabout != null -> {
                val rad = r.width() * 0.2f
                c.drawCircle(cx, mid, rad, p)
                c.drawLine(cx, r.bottom, cx, mid + rad, p)
                // 진입(아래)에서 반시계로 exit 각도 → 화면 각도
                val a = Math.toRadians(90.0 + roundabout)
                val ex = cx + (rad * cos(a)).toFloat()
                val ey = mid - (rad * sin(a)).toFloat()
                val tx = cx + (rad * 2.1 * cos(a)).toFloat()
                val ty = mid - (rad * 2.1 * sin(a)).toFloat()
                c.drawLine(ex, ey, tx, ty, p)
                head(c, tx, ty, 90.0 - Math.toDegrees(a) + 90.0, r.width(), p)
            }
            else -> {
                val deg = CarManeuver.angle(type).toDouble() // 0 직진, 음수 왼쪽, 양수 오른쪽
                c.drawLine(cx, r.bottom, cx, mid, p)
                val len = r.height() * 0.38f
                val a = Math.toRadians(deg)
                val tx = cx + (len * sin(a)).toFloat()
                val ty = mid - (len * cos(a)).toFloat()
                c.drawLine(cx, mid, tx, ty, p)
                head(c, tx, ty, deg, r.width(), p)
            }
        }
    }

    // (x, y)에 deg 방향(0 위, 시계방향 +)을 가리키는 화살촉
    private fun head(c: Canvas, x: Float, y: Float, deg: Double, size: Float, p: Paint) {
        val len = size * 0.2f
        for (side in listOf(-150.0, 150.0)) {
            val a = Math.toRadians(deg + side)
            c.drawLine(x, y, x + (len * sin(a)).toFloat(), y - (len * cos(a)).toFloat(), p)
        }
    }
}

// 지도(KNSDK GL 렌더러)를 ImageReader에 그리게 하고, 프레임이 올 때마다 그 그림 위에 overlay를 그려 차량 화면(Surface)에 올린다.
// HardwareBuffer를 그대로 Bitmap으로 감싸 GPU에서 합성 → CPU 복사 없음. (minSdk 29: wrapHardwareBuffer, ImageReader usage)
class CarCompositor(private val overlay: (Canvas) -> Unit) {
    companion object {
        // AA 화면이 다른 생산자(재설치 전 죽은 앱 프로세스)에 붙은 채로 다시 오는 경우가 있다 → 그대로 lockHardwareCanvas 하면
        // hwui가 네이티브 abort로 앱을 죽이고, AA가 앱을 계속 다시 띄워 무한 재시작. EGL로 먼저 붙여 보고(실패해도 오류 코드만) 바로 뗀다
        fun usable(s: Surface): Boolean {
            val d = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (!EGL14.eglInitialize(d, null, 0, null, 0)) return true // 시험 못 하면 원래대로
            val cfg = arrayOfNulls<EGLConfig>(1)
            val n = IntArray(1)
            EGL14.eglChooseConfig(d, intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_NONE), 0, cfg, 0, 1, n, 0)
            if (n[0] == 0) return true
            val es = EGL14.eglCreateWindowSurface(d, cfg[0], s, intArrayOf(EGL14.EGL_NONE), 0)
            if (es == EGL14.EGL_NO_SURFACE) return false
            EGL14.eglDestroySurface(d, es) // 떼야 hwui가 붙을 수 있음
            return true
        }
    }

    private var thread: HandlerThread? = null
    private var reader: ImageReader? = null
    private var held: Image? = null // 화면에 올린 직전 프레임 (GPU가 다 쓸 때까지 쥐고 있다가 다음 프레임에 닫음)

    fun start(out: Surface, w: Int, h: Int): ImageReader {
        stop()
        val t = HandlerThread("car-compose").also { it.start(); thread = it }
        handler = Handler(t.looper)
        // KNSDK GL 출력이 RGBX (RGBA로 받으면 형식이 달라 프레임이 전부 버려짐)
        return ImageReader.newInstance(w, h, PixelFormat.RGBX_8888, 3, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
            .also { r ->
                reader = r
                r.setOnImageAvailableListener({ draw(it, out) }, Handler(t.looper))
            }
    }

    private fun draw(r: ImageReader, out: Surface) {
        val img = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        val hb = img.hardwareBuffer
        val bmp = hb?.let { Bitmap.wrapHardwareBuffer(it, ColorSpace.get(ColorSpace.Named.SRGB)) }
        if (bmp != null) paint(bmp, out)
        last = bmp?.let { it to out }
        hb?.close()
        held?.close()
        held = img
    }

    private fun paint(bmp: Bitmap, out: Surface) = runCatching {
        val c = out.lockHardwareCanvas()
        c.drawBitmap(bmp, 0f, 0f, null)
        overlay(c)
        out.unlockCanvasAndPost(c)
    }.onFailure { HudService.log("차량 화면 합성 실패: $it") }

    private var last: Pair<Bitmap, Surface>? = null // 직전 지도 프레임 (held가 쥐고 있는 동안 유효)
    private var handler: Handler? = null

    // 지도가 멈춰 새 프레임이 안 올 때도 덧그림(내 차 아래 글, 교통량 막대)을 바꿔 그림
    fun redraw() {
        handler?.post { last?.let { (b, out) -> paint(b, out) } }
    }

    fun stop() {
        handler = null
        last = null
        reader?.setOnImageAvailableListener(null, null)
        thread?.quitSafely()
        thread = null
        held?.close()
        held = null
        reader?.close()
        reader = null
    }
}

// 내 차 애니메이션 계산 (순수 함수라 JVM 테스트 가능)
object CarPath {
    // 점들을 이은 길에서 전체 길이의 f(0~1) 지점과, 그 앞에 지난 마지막 꼭짓점 번호
    fun along(pts: List<Pair<Float, Float>>, f: Float): Pair<Pair<Float, Float>, Int> {
        if (pts.size < 2) return pts.first() to 0
        val seg = pts.zipWithNext { a, b -> Math.hypot((b.first - a.first).toDouble(), (b.second - a.second).toDouble()).toFloat() }
        var left = seg.sum() * f.coerceIn(0f, 1f)
        for ((i, len) in seg.withIndex()) {
            if (left <= len || i == seg.lastIndex) {
                val k = if (len > 0f) (left / len).coerceIn(0f, 1f) else 1f
                val a = pts[i]
                val b = pts[i + 1]
                return (a.first + (b.first - a.first) * k to a.second + (b.second - a.second) * k) to i
            }
            left -= len
        }
        return pts.last() to pts.lastIndex - 1
    }

    // 속도(km/h) → 축척 배율: 30까지 1배, 110에서 2.2배 (그 위는 그대로). 시내는 자세히, 고속도로는 멀리
    fun speedZoom(kmh: Int): Float = 1f + (kmh - 30).coerceIn(0, 80) / 80f * 1.2f

    // 축척 보간은 배율로 (1→2와 2→4가 같은 빠르기로 보이게)
    fun lerpZoom(a: Float, b: Float, f: Float): Float = a * Math.pow((b / a).toDouble(), f.toDouble()).toFloat()

    // KNTrafficState 이름 → 교통량 막대 색. 원활은 초록(SDK 이름은 Blue), 정보 없으면 회색
    fun trafficColor(state: String): Int = when (state.removePrefix("KNTrafficState_")) {
        "Blue" -> 0xFF69F0AE.toInt()
        "Yellow" -> 0xFFFFF176.toInt()
        "Orange" -> 0xFFFFB74D.toInt()
        "Red" -> 0xFFFF6E6E.toInt()
        "Blocked" -> 0xFFD32F2F.toInt()
        else -> 0xFFB0B8C4.toInt()
    }

    // 남은 경로를 같은 간격으로 짚은 교통 상태(가까운 곳부터) → (끝 비율 0~1, 색) 칸. 같은 색이 이어지면 한 칸
    fun trafficRuns(states: List<String>): List<Pair<Float, Int>> {
        val out = mutableListOf<Pair<Float, Int>>()
        states.forEachIndexed { i, s ->
            val c = trafficColor(s)
            val end = (i + 1f) / states.size
            if (out.lastOrNull()?.second == c) out[out.lastIndex] = end to c else out += end to c
        }
        return out
    }

    // 각도(도) 보간: 짧은 쪽으로 (350 → 10은 0을 지나감)
    fun lerpAngle(a: Float, b: Float, f: Float): Float = (a + turn(a, b) * f + 360f) % 360f

    // pts[i]부터 앞쪽으로 길이가 있는 첫 마디의 방향 (북 0, 시계 방향; x 동·y 북 좌표). 없으면 뒤쪽 마디, 그것도 없으면 null
    fun heading(pts: List<Pair<Float, Float>>, i: Int): Float? {
        for (j in (i until pts.size - 1) + (i - 1 downTo 0)) {
            val dx = pts[j + 1].first - pts[j].first
            val dy = pts[j + 1].second - pts[j].second
            if (dx != 0f || dy != 0f) return (Math.toDegrees(atan2(dx, dy).toDouble()).toFloat() + 360f) % 360f
        }
        return null
    }

    // a에서 b로 가장 짧게 도는 각 (-180~180, +는 시계 방향)
    fun turn(a: Float, b: Float): Float = ((b - a) % 360f + 540f) % 360f - 180f
}

// KNRGCode 값 → AA Maneuver 타입 (+ 로터리 출구 각도). 순수 함수라 JVM 테스트 가능
object CarManeuver {
    // TMAP 차로 비트(1 유턴, 2 좌, 4 좌측, 8 직진, 16 우측, 32 우) → AA 차로 방향 + 그림용 Maneuver 타입
    class LaneDir(val bit: Int, val shape: Int, val arrow: Int)

    private val LANE_BITS = listOf(
        LaneDir(1, LaneDirection.SHAPE_U_TURN_LEFT, Maneuver.TYPE_U_TURN_LEFT),
        LaneDir(2, LaneDirection.SHAPE_NORMAL_LEFT, Maneuver.TYPE_TURN_NORMAL_LEFT),
        LaneDir(4, LaneDirection.SHAPE_SLIGHT_LEFT, Maneuver.TYPE_TURN_SLIGHT_LEFT),
        LaneDir(8, LaneDirection.SHAPE_STRAIGHT, Maneuver.TYPE_STRAIGHT),
        LaneDir(16, LaneDirection.SHAPE_SLIGHT_RIGHT, Maneuver.TYPE_TURN_SLIGHT_RIGHT),
        LaneDir(32, LaneDirection.SHAPE_NORMAL_RIGHT, Maneuver.TYPE_TURN_NORMAL_RIGHT),
    )

    fun laneDirs(bits: Int) = LANE_BITS.filter { bits and it.bit != 0 }

    fun of(rg: Int): Pair<Int, Int?> = when (rg) {
        1 -> Maneuver.TYPE_TURN_NORMAL_LEFT to null
        2 -> Maneuver.TYPE_TURN_NORMAL_RIGHT to null
        3 -> Maneuver.TYPE_U_TURN_LEFT to null
        5 -> Maneuver.TYPE_TURN_SLIGHT_LEFT to null
        6 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT to null
        7, 9, 42, 44 -> Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT to null
        8, 43 -> Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT to null
        10, 12, 45, 47 -> Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT to null
        11, 46 -> Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT to null
        48 -> Maneuver.TYPE_FORK_LEFT to null
        49 -> Maneuver.TYPE_FORK_RIGHT to null
        61 -> Maneuver.TYPE_FERRY_BOAT to null
        82 -> Maneuver.TYPE_KEEP_LEFT to null
        83 -> Maneuver.TYPE_KEEP_RIGHT to null
        100 -> Maneuver.TYPE_DEPART to null
        101, 1000 -> Maneuver.TYPE_DESTINATION to null
        in 18..29 -> clock(rg - 17) to null
        // 한국은 우측통행 → 반시계 로터리. 출구 각도 = n시 × 30°
        in 30..41 -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE to (rg - 29) * 30
        in 70..81 -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE to (rg - 69) * 30
        else -> Maneuver.TYPE_STRAIGHT to null
    }

    private fun clock(h: Int) = when (h) {
        1, 2 -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
        3 -> Maneuver.TYPE_TURN_NORMAL_RIGHT
        4, 5 -> Maneuver.TYPE_TURN_SHARP_RIGHT
        6 -> Maneuver.TYPE_U_TURN_LEFT
        7, 8 -> Maneuver.TYPE_TURN_SHARP_LEFT
        9 -> Maneuver.TYPE_TURN_NORMAL_LEFT
        10, 11 -> Maneuver.TYPE_TURN_SLIGHT_LEFT
        else -> Maneuver.TYPE_STRAIGHT
    }

    // 화살표 그림 각도 (0 직진, 음수 왼쪽, 양수 오른쪽)
    fun angle(type: Int) = when (type) {
        Maneuver.TYPE_TURN_NORMAL_LEFT -> -90
        Maneuver.TYPE_TURN_NORMAL_RIGHT -> 90
        Maneuver.TYPE_TURN_SLIGHT_LEFT, Maneuver.TYPE_KEEP_LEFT, Maneuver.TYPE_FORK_LEFT,
        Maneuver.TYPE_OFF_RAMP_SLIGHT_LEFT, Maneuver.TYPE_ON_RAMP_SLIGHT_LEFT -> -45
        Maneuver.TYPE_TURN_SLIGHT_RIGHT, Maneuver.TYPE_KEEP_RIGHT, Maneuver.TYPE_FORK_RIGHT,
        Maneuver.TYPE_OFF_RAMP_SLIGHT_RIGHT, Maneuver.TYPE_ON_RAMP_SLIGHT_RIGHT -> 45
        Maneuver.TYPE_TURN_SHARP_LEFT -> -135
        Maneuver.TYPE_TURN_SHARP_RIGHT -> 135
        else -> 0
    }
}

// ── 차량 화면에서 목적지 고르기 (A2): NavScreen 목록(검색·최근 목적지) → 경로 선택 → 출발 ──

// 차에서 고른 목적지 → 경로 선택 화면 (설정에서 끄면 바로 안내). 폰 앱을 안 열었으면 HUD 연결 서비스도 같이 띄운다
private fun chooseFromCar(ctx: CarContext, sm: androidx.car.app.ScreenManager, p: Place, simul: Boolean) {
    Recents.remember(ctx.getSharedPreferences("hud", Context.MODE_PRIVATE), p)
    if (HudService.instance == null) {
        // ponytail: 백그라운드 FGS 시작이 막히면(Android 12+) 폰에서 앱을 한 번 열어야 HUD 연결됨. 안내 자체는 진행
        runCatching { ctx.startForegroundService(Intent(ctx, HudService::class.java)) }
            .onFailure { HudService.log("차에서 HUD 연결 시작 실패: $it") }
    }
    if (KakaoGuidance.routePick) return sm.push(RouteScreen(ctx, p, simul))
    KakaoGuidance.init(ctx.applicationContext as Application) { err ->
        if (err != null) return@init CarToast.makeText(ctx, "카카오 초기화 실패 ${err.code}", CarToast.LENGTH_LONG).show()
        KakaoGuidance.start(p, simul) { e -> if (e != null) CarToast.makeText(ctx, e, CarToast.LENGTH_LONG).show() }
    }
}

// 차에서 안전운행 시작 (HUD 연결 서비스도 같이)
private fun safeFromCar(ctx: CarContext) {
    if (HudService.instance == null) runCatching { ctx.startForegroundService(Intent(ctx, HudService::class.java)) }
        .onFailure { HudService.log("차에서 HUD 연결 시작 실패: $it") }
    KakaoGuidance.init(ctx.applicationContext as Application) { err ->
        if (err != null) return@init CarToast.makeText(ctx, "카카오 초기화 실패 ${err.code}", CarToast.LENGTH_LONG).show()
        KakaoGuidance.safeDrive()
    }
}

// 경로 선택: 종류별 시간·거리·통행료 목록, 고르면 지도에 그 경로 전체, '출발'로 안내 시작
class RouteScreen(ctx: CarContext, private val to: Place, private val simul: Boolean) : Screen(ctx) {
    private var choices: List<KakaoGuidance.RouteChoice>? = null
    private var selected = 0

    init {
        KakaoGuidance.init(ctx.applicationContext as Application) { err ->
            if (err != null) return@init fail("카카오 초기화 실패 ${err.code}")
            KakaoGuidance.plan(to) { list, e ->
                if (!alive) return@plan // 찾는 동안 뒤로 나감
                if (e != null) return@plan fail(e)
                choices = list
                KakaoGuidance.preview = listOf(list[0].route)
                invalidate()
            }
        }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // 뒤로 나가면 미리보기 경로 지움 (출발하면 go()에서 이미 지움)
            override fun onDestroy(owner: LifecycleOwner) { KakaoGuidance.preview = null }
        })
    }

    // 닫힌 뒤 늦게 오는 콜백(경로 탐색 결과, AA 선택 알림)이 미리보기를 다시 켜면 안내가 끝난 뒤 지도가 미리보기로 굳음 (0.1.6 DHU)
    private val alive get() = lifecycle.currentState != Lifecycle.State.DESTROYED

    private fun fail(msg: String) {
        CarToast.makeText(carContext, msg, CarToast.LENGTH_LONG).show()
        screenManager.pop()
    }

    override fun onGetTemplate(): Template {
        val b = RoutePreviewNavigationTemplate.Builder().setTitle(to.name).setHeaderAction(Action.BACK)
        val list = choices ?: return b.setLoading(true).build()
        val items = ItemList.Builder().setOnSelectedListener { i ->
            if (!alive) return@setOnSelectedListener
            selected = i
            KakaoGuidance.preview = listOf(list[i].route)
        }
        // 이 템플릿은 줄마다 시간·거리 span이 있어야 한다 (없으면 IllegalArgumentException으로 앱 종료, 0.1.5 실기기)
        for (c in list) {
            val text = SpannableString(KakaoNaviMapping.routeSummary(c.route.totalTime, c.route.totalDist, c.route.totalCost))
            text.setSpan(DurationSpan.create(c.route.totalTime.toLong()), 0, text.indexOf(" · "), Spanned.SPAN_INCLUSIVE_EXCLUSIVE)
            items.addItem(Row.Builder().setTitle(c.label).addText(text).build())
        }
        return b.setItemList(items.setSelectedIndex(selected).build())
            .setNavigateAction(Action.Builder().setTitle("출발").setOnClickListener {
                KakaoGuidance.go(list[selected], simul)
                screenManager.popToRoot()
            }.build())
            .build()
    }
}

private fun placeList(ctx: CarContext, places: List<Place>, empty: String, onPick: (Place) -> Unit): ItemList {
    val limit = ctx.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    return ItemList.Builder().apply {
        setNoItemsMessage(empty)
        for (p in places.take(limit)) addItem(
            Row.Builder().setTitle(p.name).addText(p.address.ifEmpty { " " }).setOnClickListener { onPick(p) }.build(),
        )
    }.build()
}

class SearchScreen(ctx: CarContext, private val simul: () -> Boolean) : Screen(ctx) {
    private var results: List<Place>? = null
    private var loading = false

    override fun onGetTemplate(): Template {
        val b = SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchSubmitted(text: String) {
                if (text.isBlank()) return
                loading = true
                invalidate()
                KakaoGuidance.init(carContext.applicationContext as Application) { err ->
                    if (err != null) { loading = false; results = emptyList(); invalidate(); return@init }
                    KakaoGuidance.search(text) { results = it; loading = false; invalidate() }
                }
            }
        }).setHeaderAction(Action.BACK).setSearchHint("장소·주소").setShowKeyboardByDefault(results == null)
        if (loading) b.setLoading(true)
        else results?.let { r -> b.setItemList(placeList(carContext, r, "검색 결과 없음") { chooseFromCar(carContext, screenManager, it, simul()) }) }
        return b.build()
    }
}

// ── 주행 중 옵션: 안내 음성 크기, 주변 주유소·편의점 (고르면 경유지로) ──
class OptionsScreen(ctx: CarContext) : Screen(ctx) {
    private val levels = listOf("크게" to 1f, "중간" to 0.6f, "작게" to 0.3f)

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
        // 차량 목록은 6줄까지만 보장 → 음성 크기는 한 줄에서 눌러 크게→중간→작게 순환
        val cur = levels.indexOfFirst { abs(KakaoGuidance.volume - it.second) < 0.01f }.coerceAtLeast(0)
        list.addItem(
            Row.Builder().setTitle("안내 음성: ${levels[cur].first}").addText("누르면 크게 → 중간 → 작게")
                .setOnClickListener { KakaoGuidance.volume = levels[(cur + 1) % levels.size].second; invalidate() }.build(),
        )
        list.addItem(
            Row.Builder().setTitle("음성 나올 때 음악 줄이기: ${if (KakaoGuidance.duck) "켜짐" else "꺼짐"}")
                .setOnClickListener { KakaoGuidance.duck = !KakaoGuidance.duck; invalidate() }.build(),
        )
        list.addItem(
            Row.Builder().setTitle("최소 안내: ${if (KakaoGuidance.minimal) "켜짐" else "꺼짐"}").addText("회전 안내 음성을 줄임 (카메라 안내는 그대로)")
                .setOnClickListener { KakaoGuidance.minimal = !KakaoGuidance.minimal; invalidate() }.build(),
        )
        // 지도에 주유소 가격: 끔 → 휘발유 → 경유
        val prefs = carContext.getSharedPreferences("hud", Context.MODE_PRIVATE)
        val fuel = Opinet.FUELS.indexOfFirst { it.first == Opinet.fuel(prefs) }.coerceAtLeast(0)
        list.addItem(
            Row.Builder().setTitle("지도에 주유소 가격: ${Opinet.FUELS[fuel].second}")
                .addText(if (ApiKeys.opinet(prefs).isEmpty()) "폰 앱 API 키 화면에 오피넷 키 입력 필요" else "누르면 끔 → 휘발유 → 경유")
                .setOnClickListener { prefs.edit().putString(Opinet.FUEL, Opinet.FUELS[(fuel + 1) % Opinet.FUELS.size].first).apply(); invalidate() }.build(),
        )
        for (q in listOf("주유소", "편의점")) list.addItem(
            Row.Builder().setTitle("가까운 $q").setBrowsable(true).setOnClickListener { screenManager.push(NearbyScreen(carContext, q)) }.build(),
        )
        return ListTemplate.Builder().setTitle("옵션").setHeaderAction(Action.BACK).setSingleList(list.build()).build()
    }
}

class NearbyScreen(ctx: CarContext, private val q: String) : Screen(ctx) {
    private var results: List<Place>? = null

    init {
        KakaoGuidance.nearby(q) { results = it; invalidate() }
    }

    override fun onGetTemplate(): Template {
        val b = ListTemplate.Builder().setTitle("가까운 $q").setHeaderAction(Action.BACK)
        val r = results ?: return b.setLoading(true).build()
        return b.setSingleList(placeList(carContext, r, "근처에 $q 없음") { p ->
            KakaoGuidance.addVia(p) { e -> CarToast.makeText(carContext, e ?: "경유지: ${p.name}", CarToast.LENGTH_LONG).show() }
            screenManager.popToRoot()
        }).build()
    }
}
