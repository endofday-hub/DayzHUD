package com.dayz.hud

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
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
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.car.app.navigation.model.Trip
import androidx.car.app.validation.HostValidator
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kakaomobility.knsdk.KNSDK
import com.kakaomobility.knsdk.common.util.FloatPoint
import com.kakaomobility.knsdk.common.util.IntPoint
import com.kakaomobility.knsdk.map.knmaprenderer.objects.KNMapCameraUpdate
import com.kakaomobility.knsdk.map.knmaprenderer.renderer.idl.KNMapControlManager
import com.kakaomobility.knsdk.map.uicustomsupport.renewal.KNMapMarker
import com.kakaomobility.knsdk.map.uicustomsupport.renewal.theme.base.KNMapTheme
import com.kakaomobility.knsdk.trip.kntrip.knroute.KNRoute
import java.util.TimeZone
import kotlin.math.cos
import kotlin.math.sin

// Android Auto 내비 앱 (PLAN.md A1). 폰에서 시작한 카카오 안내(KakaoGuidance)를 차량 화면에 보여 준다.
// - 회전 카드·도착 정보: NavigationTemplate (차량 계기판·순정 HUD에도 updateTrip으로 같이 감)
// - 지도 자리: 카카오 지도 (KNSDK.makeRendererWithFrame → AA Surface)
// Play 미배포 → 폰 AA 개발자 설정 "알 수 없는 출처" 필요
class HudCarAppService : CarAppService() {
    // ponytail: 모든 호스트 허용 (사이드로드 개인용). 배포하면 서명된 호스트 목록으로
    override fun createHostValidator() = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession() = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = NavScreen(carContext)
    }
}

class NavScreen(ctx: CarContext) : Screen(ctx), SurfaceCallback {

    private val nav = ctx.getCarService(NavigationManager::class.java)
    private var navigating = false
    private var visible: Rect? = null
    private val onState: () -> Unit = { invalidate(); updateTrip(); follow() }

    init {
        nav.setNavigationManagerCallback(object : NavigationManagerCallback {
            override fun onStopNavigation() = KakaoGuidance.stop() // 차에서 다른 내비를 켜면
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

    override fun onGetTemplate(): Template {
        val pick = Action.Builder().setTitle("목적지").setOnClickListener { screenManager.push(DestinationScreen(carContext)) }.build()
        val strip = ActionStrip.Builder().addAction(pick)
        val s = KakaoGuidance.state
        if (s != null) strip.addAction(Action.Builder().setTitle("안내 종료").setOnClickListener { KakaoGuidance.stop() }.build())
        val b = NavigationTemplate.Builder().setActionStrip(strip.build())
        if (s == null) {
            b.setNavigationInfo(MessageInfo.Builder("DayzHUD").setText("'목적지'를 눌러 카카오 길안내를 시작하세요").build())
        } else {
            val info = RoutingInfo.Builder().setCurrentStep(step(s.rg, s.tbt.turnName), distance(s.tbt.turnDistance))
            s.nextRg?.let { info.setNextStep(step(it, "")) }
            b.setNavigationInfo(info.build()).setDestinationTravelEstimate(estimate(s.tbt))
        }
        return b.build()
    }

    // 차량 계기판·순정 HUD로 가는 안내 (AA가 차에 전달)
    private fun updateTrip() {
        val s = KakaoGuidance.state
        if (s == null) {
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
                .addStep(step(s.rg, t.turnName), estimate(t.turnDistance, stepSec))
                .addDestination(Destination.Builder().setName(s.destination).build(), estimate(t))
                .setCurrentRoad(s.tbt.turnName)
                .build(),
        )
    }

    private fun step(rg: Int, cue: String): Step {
        val (type, angle) = CarManeuver.of(rg)
        val m = Maneuver.Builder(type).setIcon(CarIcon.Builder(IconCompat.createWithBitmap(arrowBitmap(type, angle))).build())
        if (angle != null) m.setRoundaboutExitAngle(angle)
        return Step.Builder(cue.ifEmpty { " " }).setManeuver(m.build()).build()
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
    private var shownRoutes: List<KNRoute>? = null

    override fun onSurfaceAvailable(c: SurfaceContainer) {
        val s = c.surface ?: return
        HudService.log("차량 화면 ${c.width}x${c.height}")
        surfaceSize = c.width to c.height
        KakaoGuidance.init(carContext.applicationContext as Application) { err ->
            if (err != null) return@init HudService.log("차량 지도: 카카오 초기화 실패 ${err.code}")
            map?.onDestroy()
            map = runCatching {
                KNSDK.makeRendererWithFrame(carContext, KNMapTheme.driveNight(), c.width.toFloat(), c.height.toFloat(), s, false)
            }.onFailure { HudService.log("차량 지도 실패: $it") }.getOrNull()?.apply {
                HudService.log("차량 지도 시작 zoom=$zoom tilt=$tilt")
            }
            car = null
            shownRoutes = null
            follow()
        }
    }

    override fun onSurfaceDestroyed(c: SurfaceContainer) {
        map?.onDestroy()
        map = null
    }

    override fun onVisibleAreaChanged(r: Rect) { visible = r }

    // 내 차를 카드에 안 가리는 영역의 가운데·아래쪽 3/4 지점에 (화면 비율 0~1, SDK 화면도 같은 방식)
    private fun anchor(): FloatPoint {
        val v = visible ?: return FloatPoint(0.5f, 0.75f)
        val w = surfaceSize.first.coerceAtLeast(1).toFloat()
        val h = surfaceSize.second.coerceAtLeast(1).toFloat()
        return FloatPoint(v.exactCenterX() / w, (v.top + v.height() * 0.75f) / h)
    }

    private var surfaceSize = 0 to 0
    private var car: KNMapMarker? = null
    private val CAR_OFFSET = 36 // 실측해서 맞출 값

    // 앰버 삼각형 + 흰 테두리 (지도 위 내 차)
    private fun carIcon() = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888).also {
        val c = Canvas(it)
        val path = Path().apply { moveTo(36f, 4f); lineTo(64f, 66f); lineTo(36f, 52f); lineTo(8f, 66f); close() }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MainActivity.ACCENT })
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 4f })
    }

    // 1초마다: 경로선, 지나온 경로 지우기, 내 차 위치·방향, 카메라 따라가기
    private fun follow() {
        val m = map ?: return
        val s = KakaoGuidance.state
        val pos = s?.pos ?: KNSDK.sharedGpsManager()?.lastValidGpsData?.pos?.let { FloatPoint(it.x.toFloat(), it.y.toFloat()) } ?: return
        val angle = s?.angle ?: 0f
        if (s?.routes !== shownRoutes) {
            s?.routes?.let(m::setRoutes) ?: m.removeRoutesAll()
            shownRoutes = s?.routes
        }
        s?.location?.let { m.cullPassedRoute(it, false) } // SDK 화면과 같은 인자
        // 내 차: userLocation은 렌더러에서 안 그려져서 마커로. 카메라가 진행 방향을 위로 두므로 위쪽 화살표 고정
        // 마커는 아이콘 아래 끝이 좌표 → 반 높이만큼 내려 아이콘 가운데가 내 위치에 오게
        val c = car ?: KNMapMarker(pos).apply {
            icon = carIcon(); priority = Int.MAX_VALUE; pixelOffset = IntPoint(0, CAR_OFFSET)
        }.also { m.addMarker(it); car = it }
        c.coordinate = pos
        m.animateCamera(KNMapCameraUpdate().targetTo(pos).bearingTo(angle).anchorTo(anchor()), 900, false, false)
    }

    private fun arrowBitmap(type: Int, angle: Int?) = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888).also {
        drawArrow(Canvas(it), RectF(8f, 8f, 120f, 120f), type, angle, Color.WHITE)
    }

    // ponytail: 화살표는 방향 각도 하나로 단순화 (유턴·도착·로터리만 따로). 차선 그림은 없음
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

// KNRGCode 값 → AA Maneuver 타입 (+ 로터리 출구 각도). 순수 함수라 JVM 테스트 가능
object CarManeuver {
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

// ── 차량 화면에서 목적지 고르기 (A2): 최근 목적지 목록 → 검색 → 누르면 안내 시작 ──

// 차에서 고른 목적지로 안내 시작. 폰 앱을 안 열었으면 HUD 연결 서비스도 같이 띄운다
private fun startFromCar(ctx: CarContext, sm: androidx.car.app.ScreenManager, p: Place, simul: Boolean) {
    Recents.remember(ctx.getSharedPreferences("hud", Context.MODE_PRIVATE), p)
    if (HudService.instance == null) {
        // ponytail: 백그라운드 FGS 시작이 막히면(Android 12+) 폰에서 앱을 한 번 열어야 HUD 연결됨. 안내 자체는 진행
        runCatching { ctx.startForegroundService(Intent(ctx, HudService::class.java)) }
            .onFailure { HudService.log("차에서 HUD 연결 시작 실패: $it") }
    }
    KakaoGuidance.init(ctx.applicationContext as Application) { err ->
        if (err != null) return@init CarToast.makeText(ctx, "카카오 초기화 실패 ${err.code}", CarToast.LENGTH_LONG).show()
        KakaoGuidance.start(p, simul) { e -> if (e != null) CarToast.makeText(ctx, e, CarToast.LENGTH_LONG).show() }
    }
    sm.popToRoot()
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

class DestinationScreen(ctx: CarContext) : Screen(ctx) {
    private var simul = false // DHU 시험용

    override fun onGetTemplate(): Template {
        val prefs = carContext.getSharedPreferences("hud", Context.MODE_PRIVATE)
        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("검색").setOnClickListener {
                screenManager.push(SearchScreen(carContext) { simul })
            }.build())
            .addAction(Action.Builder().setTitle(if (simul) "모의주행 켜짐" else "모의주행").setOnClickListener {
                simul = !simul
                invalidate()
            }.build())
            .build()
        return ListTemplate.Builder()
            .setTitle("최근 목적지")
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip)
            .setSingleList(placeList(carContext, Recents.load(prefs), "최근 목적지 없음 — '검색'을 누르세요") {
                startFromCar(carContext, screenManager, it, simul)
            })
            .build()
    }
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
        else results?.let { r -> b.setItemList(placeList(carContext, r, "검색 결과 없음") { startFromCar(carContext, screenManager, it, simul()) }) }
        return b.build()
    }
}
