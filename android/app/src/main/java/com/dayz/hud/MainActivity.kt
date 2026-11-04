package com.dayz.hud

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.LinearLayout.LayoutParams
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt

// 레이아웃 XML·AndroidX 없이 코드로 그린다. 카드 단위: 상태 → 내비 연동 → HUD 화면 → (접힘) 테스트·로그·수집
class MainActivity : Activity() {

    private lateinit var hudState: TextView
    private lateinit var notiState: TextView
    private lateinit var tmapState: TextView
    private lateinit var tmapBtn: TextView
    private lateinit var notiHelp: View
    private lateinit var connectBtn: TextView
    private lateinit var dumpInfo: TextView
    private lateinit var logView: TextView
    private lateinit var tbtInput: EditText
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("hud", MODE_PRIVATE) }
    private var hud = HudSettings()
    private val kivic get() = HudService.instance?.kivic
    private var afterGrant: (() -> Unit)? = null // 권한 허용되면 이어서 할 일

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        actionBar?.hide() // 화면 안에 제목이 따로 있다

        hudState = text("", 15f, bold = true)
        notiState = text("", 15f, bold = true)
        tmapState = text("", 15f, bold = true)
        // 인증 한도 때문에 기본 내비가 TMAP이 아니면 누를 때만 연동 (실패 후 다시 시도도 이 버튼)
        // ponytail: 다시 시도는 finish 직후 바로 initialize — SDK가 싫어하면 onFinishedEDC 뒤에 시작하도록
        tmapBtn = outlineButton("TMAP 연동") { HudService.instance?.tmap?.run { if (state != TmapSource.State.OFF) stop(); start() } }
        connectBtn = button("") {
            if (HudService.instance == null) {
                withBle { startForegroundService(Intent(this, HudService::class.java)) }
            } else stopService(Intent(this, HudService::class.java))
        }
        notiHelp = column(
            text("네이버지도 안내·카톡·문자를 받으려면 알림 접근을 허용하세요.\n" +
                "DayzHUD가 회색이면: 설정 > 앱 > DayzHUD > ⋮ > '제한된 설정 허용'\n" +
                "허용했는데 '연결 안 됨'이면 (샤오미): '자동 시작 설정'에서 DayzHUD 켜기", 13f, SUB),
            row(
                outlineButton("알림 접근 설정") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                // HyperOS는 앱 정보가 아니라 보안 앱의 '백그라운드 자동 시작' 목록. 다른 폰이면 앱 정보로
                outlineButton("자동 시작 설정") {
                    runCatching { startActivity(Intent().setClassName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")) }
                        .onFailure { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
                },
            ),
        )
        logView = text("", 11f, SUB).apply { typeface = Typeface.MONOSPACE }
        dumpInfo = text("", 13f, SUB)
        tbtInput = EditText(this).apply {
            hint = "코드 거리 [displayStatus naviStatus]"
            setText("12 300 1 1")
            setTextColor(TEXT)
            setHintTextColor(SUB)
            background = rounded(CHIP, 10)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        val root = column(
            text("DayzHUD", 26f, bold = true),
            text("Kivic SM HUD 내비 연동", 14f, SUB),
            card(
                "연결 상태",
                statusRow("HUD", hudState),
                statusRow("TMAP", tmapState).apply { addView(tmapBtn, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(10) }) },
                statusRow("알림 접근", notiState),
                notiHelp,
                connectBtn.spaced(14),
            ),
            card(
                "내비 연동",
                navRow("TMAP", "회전·차로·제한속도·카메라 등 전체 (기본 내비가 TMAP이면 자동 연동, 아니면 연결 상태의 TMAP 연동 버튼)", null),
                navRow("네이버지도", "Android Auto 연결 중 자동 표시 (회전·거리·속도)", null),
                navRow("DayzHUD 길안내", "카카오내비 SDK로 앱 안에서 길안내 · Android Auto 지도", null),
                // 카카오내비 SDK: 목적지 검색 → 앱 안에서 길안내 (모의주행은 시험용)
                row(
                    outlineButton("카카오 길안내") { kakaoNavi(simul = false) },
                    outlineButton("모의주행") { kakaoNavi(simul = true) },
                ),
                field("앱 실행 시 자동 실행", segments(NAV_APPS.map { it.first }, NAV_APPS.indexOf(navApp(prefs))) {
                    prefs.edit().putInt("navApp", it).apply()
                }),
            ),
            card("HUD 화면", *settingsSection().toTypedArray()),
            fold("API 키", *keysSection().toTypedArray()),
            fold("도움말", *helpSection().toTypedArray()),
            fold(
                "테스트 도구",
                tbtInput,
                row(button("TBT 전송") { sendTbt(tbtFromInput()) }, outlineButton("안내 종료") { sendTbt(TmapMapping.ENDED) }),
                outlineButton("회전코드 순회 (3초 간격)") { sweep(0) }.spaced(8),
                outlineButton("카톡 알림 시험") {
                    kivic?.sendFrames(listOf(KivicPacket.social(KivicPacket.KAKAO, "", "DayzHUD : 카톡 알림 시험"))) ?: log("먼저 HUD 연결")
                }.spaced(8),
            ),
            fold("로그", logView),
            fold(
                "알림 수집 (개발용)",
                dumpInfo,
                row(
                    outlineButton("zip 내보내기") { export() },
                    outlineButton("기록 삭제") { dumpDir(this).deleteRecursively(); refresh() },
                ),
            ),
        ).apply { setPadding(dp(16), dp(20), dp(16), dp(32)) }

        setContentView(ScrollView(this).apply { setBackgroundColor(BG); addView(root) })
        HudService.onLog = {
            logView.text = HudService.logLines.joinToString("\n")
            refresh() // 연결 상태가 바뀔 때마다 로그가 남는다
        }
        HudService.onLog?.invoke()
    }

    // 앱을 열 때마다 (Android 12+는 뒤로가기로 나가도 화면이 남아 onCreate가 안 불린다).
    // 이미 연결 중이면 내비에서 돌아온 것 → 아무것도 안 함. 연결 유지 알림으로 들어온 경우도 제외
    override fun onStart() {
        super.onStart()
        if (intent.action == Intent.ACTION_MAIN && HudService.instance == null) withBle(::autoStart)
    }

    // HUD 연결 + 기본 내비 실행. TMAP 연동은 서비스가 항상 켜지만 TMAP 화면은 안 띄운다 → 직접 실행
    private fun autoStart() {
        val (name, pkg) = navApp(prefs)
        startForegroundService(Intent(this, HudService::class.java))
        if (pkg == null) return
        packageManager.getLaunchIntentForPackage(pkg)?.let(::startActivity) ?: log("$name 미설치")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }) afterGrant?.invoke()
        afterGrant = null
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        HudService.onLog = null // 연결은 HudService가 계속 유지
        super.onDestroy()
    }

    private fun refresh() {
        val svc = HudService.instance
        val (label, color) = when {
            svc == null -> "꺼짐" to SUB
            svc.kivic.ready -> "연결됨" to OK
            else -> "연결 중…" to ACCENT
        }
        hudState.text = "● $label"
        hudState.setTextColor(color)
        // TMAP 연동은 HUD 서비스가 켜질 때 같이 시작
        val tmap = svc?.tmap?.state ?: TmapSource.State.OFF
        tmapState.text = "● ${tmap.label}"
        tmapState.setTextColor(
            when (tmap) {
                TmapSource.State.READY, TmapSource.State.GUIDING -> OK
                TmapSource.State.CONNECTING -> ACCENT
                TmapSource.State.OFF -> SUB
                else -> BAD
            },
        )
        val idle = tmap == TmapSource.State.OFF || tmap == TmapSource.State.AUTH_FAILED || tmap == TmapSource.State.FAILED
        tmapBtn.visibility = if (svc != null && idle) View.VISIBLE else View.GONE
        connectBtn.text = if (svc == null) "HUD 연결" else "연결 종료"
        connectBtn.background = if (svc == null) pressable(rounded(ACCENT, 12)) else pressable(rounded(CHIP, 12))
        connectBtn.setTextColor(if (svc == null) Color.BLACK else TEXT)

        val granted = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, NavNotificationListener::class.java).flattenToString()) == true
        // 허용돼 있어도 시스템이 리스너를 안 붙일 수 있다 (HyperOS 자동 시작 꺼짐 → 'Reject service')
        val live = granted && NavNotificationListener.live
        notiState.text = when { live -> "● 허용됨"; granted -> "● 연결 안 됨"; else -> "● 필요" }
        notiState.setTextColor(if (live) OK else BAD)
        notiHelp.visibility = if (live) View.GONE else View.VISIBLE

        val dir = dumpDir(this)
        val records = File(dir, LOG).takeIf { it.exists() }?.useLines { it.count() } ?: 0
        val icons = File(dir, "icons").list()?.size ?: 0
        dumpInfo.text = "기록 ${records}건 · 아이콘 ${icons}개"
    }

    // ── HUD 화면 설정: 바꿀 때마다 저장 + 연결돼 있으면 즉시 전송 (연결 시에도 자동 전송) ──

    private fun settingsSection(): List<View> {
        hud = HudSettings.load(prefs)
        val colors = listOf(HudSettings.WHITE, HudSettings.CYAN, HudSettings.GREEN)
        return listOf(
            field("테마 색상", segments(listOf("앰버", "시안", "핑크", "옐로", "그린"), hud.theme, THEME_COLORS) { update { copy(theme = it) } }),
            field("내비 테마", segments(listOf("일반", "자동차"), hud.navTheme) { update { copy(navTheme = it) } }),
            slider("화면 크기", hud.scale / 0.2f) { update { copy(scale = it * 0.2f) } },
            slider("키스톤 (사다리꼴 보정)", hud.keystone / 0.1f) { update { copy(keystone = it * 0.1f) } },
            toggle("속도 표시", hud.showSpeed) { update { copy(showSpeed = it) } },
            toggle("속도 게이지", hud.speedGauge) { update { copy(speedGauge = it) } },
            field("속도 색상", segments(listOf("흰색", "시안", "초록"), colors.indexOf(hud.speedColor).coerceAtLeast(0),
                listOf(Color.WHITE, HudSettings.CYAN, HudSettings.GREEN)) { update { copy(speedColor = colors[it]) } }),
            toggle("시계 표시", hud.clock) { update { copy(clock = it) } },
            toggle("화면 뒤집기", hud.reverse) { update { copy(reverse = it) } },
            toggle("전체 화면", hud.fullScreen) { update { copy(fullScreen = it) } },
            toggle("자동 밝기", hud.autoBrightness) { update { copy(autoBrightness = it) } },
            slider("수동 밝기 (자동 밝기 끈 경우)", hud.brightness / 255f) { update { copy(brightness = (it * 255).toInt()) } },
            // 알림 접근 허용이 있어야 읽힘. 카톡 알림 미리보기를 끄면 내용 대신 '새 메시지'만 옴
            toggle("카톡 알림 표시", hud.kakaoTalk) { update { copy(kakaoTalk = it) } },
            toggle("문자 알림 표시", hud.sms) { update { copy(sms = it) } },
            outlineButton("설정 다시 전송") { kivic?.sendFrames(KivicPacket.settings(hud)) ?: log("먼저 HUD 연결") }.spaced(16),
        )
    }

    private fun update(change: HudSettings.() -> HudSettings) {
        val next = hud.change()
        if (next == hud) return
        hud = next
        hud.save(prefs)
        kivic?.sendFrames(KivicPacket.settings(hud))
    }

    // ── API 키: 앱에서 입력 (비우면 빌드 때 넣은 키). 값은 가려서 표시 ──

    private fun keysSection(): List<View> {
        val tmap = keyInput(ApiKeys.TMAP, BuildConfig.TMAP_API_KEY)
        val kakao = keyInput(ApiKeys.KAKAO, BuildConfig.KAKAO_APP_KEY)
        return listOf(
            text("비워 두면 빌드 때 넣은 키(secrets.properties)를 씁니다.", 13f, SUB),
            field("TMAP API 키 (appKey)", tmap),
            field("카카오 네이티브 앱 키", kakao),
            button("저장") {
                prefs.edit().putString(ApiKeys.TMAP, tmap.text.toString().trim()).putString(ApiKeys.KAKAO, kakao.text.toString().trim()).apply()
                // TMAP은 바로 다시 인증. 카카오 SDK는 한 번 초기화되면 키를 못 바꿈 → 앱을 다시 시작해야
                HudService.instance?.tmap?.run { if (state != TmapSource.State.OFF) { stop(); start() } }
                toast("저장함. 카카오 키는 앱을 완전히 닫고 다시 열면 적용")
            }.spaced(14),
        )
    }

    private fun keyInput(pref: String, built: String) = EditText(this).apply {
        setText(prefs.getString(pref, ""))
        hint = if (built.isEmpty()) "입력 필요" else "비우면 빌드 키 사용"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        typeface = Typeface.DEFAULT // 비밀번호 칸은 고정폭 글꼴이 기본 (setSingleLine은 쓰면 가림이 풀림)
        setTextColor(TEXT)
        setHintTextColor(SUB)
        background = rounded(CHIP, 10)
        setPadding(dp(12), dp(10), dp(12), dp(10))
    }

    // ── 도움말: 사용 순서, API 키 발급 (README.md와 같은 내용) ──

    private fun helpSection(): List<View> {
        val hash = keyHash()
        return listOf(
            text("사용 순서", 14f, ACCENT, bold = true),
            text("1. HUD를 켜고 'HUD 연결' (처음엔 블루투스·위치·알림 권한 허용). 원본 Kivic 앱이 HUD에 붙어 있으면 연결이 안 됩니다.\n" +
                "2. 내비 고르기 — TMAP: TMAP 앱 안내를 받아 옴 (유료 API 키 필요) / 네이버지도: Android Auto 연결 중 자동 / 카카오: '카카오 길안내'로 앱 안에서 안내, 차에선 Android Auto의 DayzHUD\n" +
                "3. 즐겨찾기: 카카오 길안내 검색 화면에서 ☆를 누르면 맨 위에 고정 (차량 목록에도 ★로 표시)\n" +
                "4. '앱 실행 시 자동 실행'으로 평소 쓰는 내비를 고르면 앱을 열 때 HUD 연결과 함께 실행\n" +
                "5. 카톡·문자 알림: 알림 접근을 허용하면 HUD에 10초 표시 ('HUD 화면'에서 끄기). 카톡 미리보기를 끄면 내용은 안 나옴", 13f, SUB),
            text("TMAP API 키 (EDC)", 14f, ACCENT, bold = true).spaced(16),
            text("무료 요금제는 하루 1건이라 실제로는 못 씁니다. Premium 요금제 가입 필요 (1건당 110원).\n" +
                "1. SK open API(openapi.sk.com) 회원 가입 → 로그인\n" +
                "2. 앱 사용하기: 앱을 만들고 앱키(appKey) 발급\n" +
                "3. 상품 사용하기: TMAP API 상품 구매 → Premium 요금제\n" +
                "4. 앱키를 'API 키'의 TMAP 칸에 입력 → 저장 → 'TMAP 연동'\n" +
                "폰에 TMAP 앱이 있어야 합니다. 인증 실패면 상품 구매·요금제 상태를 확인하세요.", 13f, SUB),
            row(link("요금 안내", "https://openapi.sk.com/products/calc?svcSeq=4&menuSeq=5"),
                link("사용자 가이드", "https://openapi.sk.com/products/detail?svcSeq=60&menuSeq=126")).spaced(8),
            text("네이버지도 (API 키 필요 없음)", 14f, ACCENT, bold = true).spaced(16),
            text("Android Auto에 연결해 네이버지도로 안내할 때만 됩니다. 폰만으로 안내하면 알림에 회전 정보가 없습니다.\n" +
                "1. '알림 접근 설정 열기' → DayzHUD 허용 (회색으로 막혀 있으면 설정 > 앱 > DayzHUD > ⋮ > '제한된 설정 허용' 후 다시)\n" +
                "2. 'HUD 연결' 후 차(Android Auto)에서 네이버지도로 안내 시작 → HUD에 회전·거리, 속도는 폰 GPS\n" +
                "남은 거리·도착 시각·제한속도는 차량 화면에만 있어 못 가져옵니다. 처음 보는 회전 아이콘은 화살표 없이 거리만 나오고 로그에 '네이버 새 회전 아이콘'으로 남습니다.", 13f, SUB),
            text("카카오 네이티브 앱 키 (카카오내비 SDK)", 14f, ACCENT, bold = true).spaced(16),
            text("개인·비상업은 무료 (실시간 길안내 하루 100,000건), 별도 신청 없음.\n" +
                "1. 카카오 디벨로퍼스 로그인 → 내 애플리케이션 → 애플리케이션 추가\n" +
                "2. 플랫폼 > Android 등록: 패키지명 $packageName, 키 해시는 아래 값 (누르면 복사)\n" +
                "3. 앱 키 > 네이티브 앱 키를 'API 키'의 카카오 칸에 입력 → 저장 → 앱을 완전히 닫고 다시 열기\n" +
                "C103 오류면 패키지명·키 해시 등록을 확인하세요.", 13f, SUB),
            // 키 해시는 APK에 든 서명 인증서에서 나오는 값이라 비밀이 아님 (앱 키는 'API 키' 칸에만, 가려서)
            text("이 앱의 키 해시 (앱 키 아님 · 보여도 괜찮은 값)", 13f, SUB).spaced(8),
            text(hash, 14f, TEXT, bold = true).apply {
                typeface = Typeface.MONOSPACE
                background = rounded(CHIP, 10)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("키 해시", hash))
                    toast("키 해시 복사함")
                }
            }.spaced(8),
            link("카카오 디벨로퍼스", "https://developers.kakao.com/console/app").spaced(8),
        )
    }

    private fun link(label: String, url: String) = outlineButton(label) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    // 카카오 콘솔에 넣는 키 해시 = 서명 인증서 SHA-1의 Base64. 빌드한 PC(서명 키)마다 다름
    private fun keyHash() = runCatching {
        packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners.orEmpty()
            .joinToString("\n") { Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(it.toByteArray()), Base64.NO_WRAP) }
    }.getOrDefault("").ifEmpty { "확인 실패" }

    // ── 화면 조각 ──

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun pressable(bg: GradientDrawable) = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), bg, null)

    private fun text(s: String, size: Float = 15f, color: Int = TEXT, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun View.spaced(top: Int) = apply {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
    }

    private fun column(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach(::addView)
    }

    // 같은 폭으로 나눠 한 줄에 — 버튼 글자가 잘리지 않게 폭을 직접 정한다
    private fun row(vararg children: View) = LinearLayout(this).apply {
        children.forEachIndexed { i, v ->
            addView(v, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
    }.spaced(10)

    private fun button(label: String, onClick: () -> Unit) = text(label, 16f, Color.BLACK, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = pressable(rounded(ACCENT, 12))
        setOnClickListener { onClick() }
    }

    private fun outlineButton(label: String, onClick: () -> Unit) = text(label, 15f, TEXT).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        background = pressable(rounded(CARD, 12, stroke = LINE))
        setOnClickListener { onClick() }
    }

    private fun card(title: String?, vararg children: View) = column().apply {
        background = rounded(CARD, 16)
        setPadding(dp(16), dp(14), dp(16), dp(16))
        title?.let { addView(text(it, 13f, ACCENT, bold = true)) }
        children.forEach(::addView)
    }.spaced(14)

    // 제목을 누르면 펼쳐지는 카드 — 자주 안 쓰는 것들
    private fun fold(title: String, vararg children: View): View {
        val body = column(*children).apply { visibility = View.GONE; setPadding(0, dp(8), 0, 0) }
        val head = text("▸  $title", 15f, TEXT, bold = true)
        head.setOnClickListener {
            val open = body.visibility != View.VISIBLE
            body.visibility = if (open) View.VISIBLE else View.GONE
            head.text = (if (open) "▾  " else "▸  ") + title
        }
        return card(null, head, body)
    }

    private fun statusRow(label: String, value: TextView) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, 0)
        addView(text(label, 15f, SUB), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        addView(value)
    }

    private fun navRow(name: String, desc: String, action: View?) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(column(text(name, 16f, bold = true), text(desc, 13f, SUB)), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        action?.let { addView(it, LayoutParams(dp(110), LayoutParams.WRAP_CONTENT).apply { marginStart = dp(8) }) }
    }

    private fun field(label: String, control: View, value: TextView? = null) = column(
        LinearLayout(this).apply {
            addView(text(label, 14f, SUB), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
            value?.let(::addView)
        },
        control.spaced(8),
    ).apply { setPadding(0, dp(14), 0, 0) }

    // 드롭다운 대신 한눈에 보이는 버튼 묶음. tints가 있으면 각 항목을 그 색으로 표시
    private fun segments(items: List<String>, selected: Int, tints: List<Int>? = null, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this)
        fun paint(sel: Int) {
            for (i in 0 until row.childCount) (row.getChildAt(i) as TextView).apply {
                val on = i == sel
                background = rounded(if (on) ACCENT else CHIP, 10)
                setTextColor(if (on) Color.BLACK else tints?.get(i) ?: TEXT)
                typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
        }
        items.forEachIndexed { i, s ->
            row.addView(text(s, 14f).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(10), 0, dp(10))
                setOnClickListener { paint(i); onPick(i) }
            }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(6) })
        }
        paint(selected)
        return row
    }

    private fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        textSize = 15f
        setTextColor(TEXT)
        isChecked = on
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        thumbTintList = ColorStateList(states, intArrayOf(ACCENT, 0xFFB0B4BC.toInt()))
        trackTintList = ColorStateList(states, intArrayOf(0x80FFB300.toInt(), 0x40FFFFFF))
        setPadding(0, dp(12), 0, dp(4))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    // 0~1 비율. 드래그 중엔 숫자만 바꾸고 손을 뗄 때 한 번 전송
    private fun slider(label: String, ratio: Float, onChange: (Float) -> Unit): View {
        val value = text("", 14f, ACCENT, bold = true)
        val bar = SeekBar(this).apply {
            max = 100
            progress = (ratio * 100).roundToInt()
            value.text = "$progress%"
            progressTintList = ColorStateList.valueOf(ACCENT)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { value.text = "$p%" }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress / 100f)
            })
        }
        return field(label, bar, value)
    }

    // ── 동작 ──

    private fun log(msg: String) = HudService.log(msg)

    private fun kakaoNavi(simul: Boolean) =
        startActivity(Intent(this, KakaoNaviActivity::class.java).putExtra(KakaoNaviActivity.EXTRA_SIMUL, simul))

    private fun sendTbt(t: Tbt) = kivic?.sendTbt(t) ?: log("먼저 HUD 연결")

    private fun withBle(action: () -> Unit) {
        val perms = if (Build.VERSION.SDK_INT >= 31) {
            listOfNotNull(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION, // GPS 속도
                // 연결 유지 알림 표시 (Android 13+)
                if (Build.VERSION.SDK_INT >= 33) Manifest.permission.POST_NOTIFICATIONS else null,
            ).toTypedArray()
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) action() else {
            afterGrant = action
            requestPermissions(missing.toTypedArray(), 1)
        }
    }

    // 남은거리/시간/속도는 표시 확인용 고정값
    private fun tbtFromInput(code: Int? = null): Tbt {
        val n = tbtInput.text.split(" ").mapNotNull { it.toIntOrNull() }
        return Tbt(
            turnCode = code ?: n.getOrElse(0) { 12 },
            turnDistance = n.getOrElse(1) { 300 },
            naviDisplayStatus = n.getOrElse(2) { 1 },
            naviStatus = n.getOrElse(3) { 1 },
            turnName = "테스트",
            speed = 60,
            totalDistance = 12_400,
            totalTime = 1_080,
        )
    }

    // TMAP TurnType 대표값 — HUD에 뜨는 아이콘을 눈으로 확인
    private val sweepCodes = listOf(11, 12, 13, 14, 16, 17, 18, 19, 101, 102, 104, 105, 117, 118, 131, 200, 201)

    private fun sweep(i: Int) {
        if (i >= sweepCodes.size) return log("순회 끝")
        sendTbt(tbtFromInput(sweepCodes[i]))
        main.postDelayed({ sweep(i + 1) }, 3_000)
    }

    private fun export() {
        val name = "dayzhud-dump-${System.currentTimeMillis()}.zip"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
        }
        val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return toast("내보내기 실패")
        val dir = dumpDir(this)
        contentResolver.openOutputStream(uri)!!.use { out ->
            ZipOutputStream(out).use { zip ->
                dir.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry(f.relativeTo(dir).path))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        toast("Download/$name 저장됨")
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    internal companion object { // 색은 KakaoNaviActivity 검색 화면도 같이 씀
        val BG = 0xFF121418.toInt()
        val CARD = 0xFF1E2128.toInt()
        val CHIP = 0xFF2A2E37.toInt()
        val LINE = 0xFF3A3F4A.toInt()
        val TEXT = 0xFFECEFF4.toInt()
        val SUB = 0xFF9AA3AF.toInt()
        val ACCENT = 0xFFFFB300.toInt() // HUD 기본 앰버
        val OK = 0xFF4CAF50.toInt()
        val BAD = 0xFFEF5350.toInt()
        // HUD 테마 색 (앰버·시안·핑크·옐로·그린) — 버튼 글자색으로 미리 보여 줌
        val THEME_COLORS = listOf(0xFFFFB300, 0xFF00E5FF, 0xFFFF4081, 0xFFFFEB3B, 0xFF69F0AE).map { it.toInt() }
        const val TMAP = "com.skt.tmap.ku"
        // prefs "navApp" = 이 목록의 순번 (뒤에만 추가). 0 = 다른 앱 안 띄움 (DayzHUD 안의 카카오 SDK 길안내). 예전 3(카카오내비 앱)은 뺌 → 0으로
        val NAV_APPS = listOf("DayzHUD" to null, "TMAP" to TMAP, "네이버지도" to NaverMapping.PKG)
        fun navApp(p: SharedPreferences) = NAV_APPS.getOrElse(p.getInt("navApp", 0)) { NAV_APPS[0] }
    }
}
