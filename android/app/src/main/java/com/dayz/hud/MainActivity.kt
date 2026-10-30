package com.dayz.hud

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var logView: TextView
    private lateinit var tbtInput: EditText
    private val main = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("hud", MODE_PRIVATE) }
    private var hud = HudSettings()
    private val kivic get() = HudService.instance?.kivic

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply { textSize = 16f }
        logView = TextView(this).apply { textSize = 12f; typeface = android.graphics.Typeface.MONOSPACE }
        tbtInput = EditText(this).apply { hint = "코드 거리 [displayStatus naviStatus]"; setText("12 300 1 1") }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(status)
            addView(button("알림 접근 권한 열기") { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) })
            addView(button("Download 폴더로 내보내기 (zip)") { export() })
            addView(button("새로고침") { refresh() })
            addView(button("기록 삭제") { dumpDir(this@MainActivity).deleteRecursively(); refresh() })

            addView(TextView(this@MainActivity).apply { text = "\n── 키빅 HUD 테스트 ──"; textSize = 16f })
            addView(button("키빅 연결 (백그라운드 유지)") {
                withBle { startForegroundService(Intent(this@MainActivity, HudService::class.java)) }
            })
            addView(button("연결 종료") { stopService(Intent(this@MainActivity, HudService::class.java)) })
            addView(tbtInput)
            addView(button("TBT 전송") { sendTbt(tbtFromInput()) })
            addView(button("회전코드 순회 (3초 간격)") { sweep(0) })
            addView(button("안내 종료 전송") { sendTbt(TmapMapping.ENDED) })
            addView(button("TMAP 연동 시작 (키빅 연결 후)") {
                HudService.instance?.tmap?.start() ?: log("먼저 키빅 연결")
            })
            addView(logView)
            settingsSection().forEach(::addView)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        HudService.onLog = { logView.text = HudService.logLines.joinToString("\n") }
        HudService.onLog?.invoke()
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

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply { text = label; setOnClickListener { onClick() } }

    // ── HUD 화면 설정: 바꿀 때마다 저장 + 연결돼 있으면 즉시 전송 (연결 시에도 자동 전송) ──

    private fun settingsSection(): List<View> {
        hud = HudSettings.load(prefs)
        val colors = listOf(HudSettings.WHITE, HudSettings.CYAN, HudSettings.GREEN)
        return listOf(
            TextView(this).apply { text = "\n── HUD 화면 설정 ──"; textSize = 16f },
            choice("테마 색상", listOf("앰버", "시안", "핑크", "옐로", "그린"), hud.theme) { update { copy(theme = it) } },
            choice("내비 테마", listOf("일반", "자동차"), hud.navTheme) { update { copy(navTheme = it) } },
            slider("화면 크기", hud.scale / 0.2f) { update { copy(scale = it * 0.2f) } },
            slider("키스톤 (사다리꼴 보정)", hud.keystone / 0.1f) { update { copy(keystone = it * 0.1f) } },
            toggle("화면 뒤집기", hud.reverse) { update { copy(reverse = it) } },
            toggle("전체 화면", hud.fullScreen) { update { copy(fullScreen = it) } },
            toggle("속도 표시", hud.showSpeed) { update { copy(showSpeed = it) } },
            toggle("속도 게이지", hud.speedGauge) { update { copy(speedGauge = it) } },
            choice("속도 색상", listOf("흰색", "시안", "초록"), colors.indexOf(hud.speedColor).coerceAtLeast(0)) {
                update { copy(speedColor = colors[it]) }
            },
            toggle("시계 표시", hud.clock) { update { copy(clock = it) } },
            toggle("자동 밝기", hud.autoBrightness) { update { copy(autoBrightness = it) } },
            slider("수동 밝기 (자동 밝기 끈 경우)", hud.brightness / 255f) { update { copy(brightness = (it * 255).toInt()) } },
            button("설정 다시 전송") { kivic?.sendFrames(KivicPacket.settings(hud)) ?: log("먼저 키빅 연결") },
        )
    }

    private fun update(change: HudSettings.() -> HudSettings) {
        val next = hud.change()
        if (next == hud) return
        hud = next
        hud.save(prefs)
        kivic?.sendFrames(KivicPacket.settings(hud))
    }

    private fun choice(label: String, items: List<String>, selected: Int, onPick: (Int) -> Unit) =
        LinearLayout(this).apply {
            addView(TextView(this@MainActivity).apply { text = "$label  " })
            addView(Spinner(this@MainActivity).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items)
                setSelection(selected, false)
                onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                    override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = onPick(pos)
                    override fun onNothingSelected(p: AdapterView<*>?) {}
                }
            })
        }

    private fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) =
        Switch(this).apply {
            text = label
            isChecked = on
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }

    // 0~1 비율. 드래그 중엔 보내지 않고 손을 뗄 때 한 번 전송
    private fun slider(label: String, ratio: Float, onChange: (Float) -> Unit) =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@MainActivity).apply { text = label })
            addView(SeekBar(this@MainActivity).apply {
                max = 100
                progress = (ratio * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) = onChange(s.progress / 100f)
                })
            })
        }

    private fun log(msg: String) = HudService.log(msg)

    private fun sendTbt(t: Tbt) = kivic?.sendTbt(t) ?: log("먼저 키빅 연결")

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
            requestPermissions(missing.toTypedArray(), 1)
            toast("권한 허용 후 다시 눌러 주세요")
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

    private fun refresh() {
        val dir = dumpDir(this)
        val records = File(dir, LOG).takeIf { it.exists() }?.useLines { it.count() } ?: 0
        val icons = File(dir, "icons").list()?.size ?: 0
        val granted = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.contains(ComponentName(this, NavNotificationListener::class.java).flattenToString()) == true
        status.text = """
            알림 접근: ${if (granted) "허용됨" else "필요 — 아래 버튼"}
            기록: ${records}건 / 아이콘: ${icons}개

            권한 화면에서 DayzHUD가 회색이면(Android 13+):
            설정 > 앱 > DayzHUD > ⋮ > '제한된 설정 허용' 후 다시 시도

            수집 순서 (앱마다):
            1) 폰 단독으로 길안내 몇 분 주행
            2) 차량 AA 연결 후 길안내 몇 분 주행
            → 좌/우회전, 유턴, 고속도로 진출, 목적지 도착이 섞이게
        """.trimIndent()
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
}
