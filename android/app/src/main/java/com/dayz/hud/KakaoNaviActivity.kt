package com.dayz.hud

import android.app.AlertDialog
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.kakaomobility.knsdk.guidance.knguidance.KNGuideState
import com.kakaomobility.knsdk.ui.view.KNNaviView
import com.kakaomobility.knsdk.ui.view.KNNaviView_GuideStateDelegate

// 카카오내비 SDK(KNSDK) 길안내 폰 화면 (PLAN.md Phase 2-K). 안내 자체는 KakaoGuidance가 들고 있고 여기선 화면만 붙인다.
// 목적지가 인텐트(EXTRA_X/Y, KATEC)에 없으면 검색 화면부터. EXTRA_SIMUL이면 모의주행
class KakaoNaviActivity : AppCompatActivity() {

    private var naviView: KNNaviView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // KNNaviView는 SDK install·초기화 뒤에 만들어야 한다 (먼저 만들면 크래시)
        KakaoGuidance.init(application) { err ->
            if (err != null) return@init HudService.log("카카오 초기화 실패: ${err.code} ${err.msg}")
            val x = intent.getIntExtra(EXTRA_X, 0)
            if (x != 0) navigate(Place(intent.getStringExtra(EXTRA_NAME).orEmpty(), x, intent.getIntExtra(EXTRA_Y, 0)))
            else showSearch()
        }
    }

    override fun onDestroy() {
        if (KakaoGuidance.view === naviView) KakaoGuidance.view = null
        // 뒤로가기로 닫으면 안내 종료 (화면 꺼짐·다른 앱 전환은 그대로 계속)
        if (isFinishing && naviView != null) KakaoGuidance.stop()
        super.onDestroy()
    }

    // ── 목적지 검색 (SDK 내장 검색, 현재 위치 기준) + 최근 목적지 ──

    private val prefs by lazy { getSharedPreferences("hud", MODE_PRIVATE) }
    private lateinit var results: LinearLayout

    private fun showSearch() {
        val input = EditText(this).apply {
            hint = "목적지 검색 (장소·주소)"
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(MainActivity.TEXT)
            setHintTextColor(MainActivity.SUB)
            // 하드웨어 엔터는 누름·뗌 두 번 온다 → 누름만
            setOnEditorActionListener { v, _, ev ->
                if (ev == null || ev.action == KeyEvent.ACTION_DOWN) search(v.text.toString())
                true
            }
        }
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
            addView(label(if (simul) "카카오 길안내 (모의주행)" else "카카오 길안내", 22f, MainActivity.TEXT))
            addView(input)
            addView(results)
        }
        setContentView(ScrollView(this).apply { setBackgroundColor(MainActivity.BG); addView(root) })
        showHistory()
        input.requestFocus()
    }

    private fun search(q: String) {
        if (q.isNotBlank()) KakaoGuidance.search(q) { list ->
            showingHistory = false
            results.removeAllViews()
            section("검색 결과 ${list.size}건", list, Favorites.load(prefs))
        }
    }

    // 검색 전: 즐겨찾기(★ 고정) 위, 최근 목적지 아래
    private var showingHistory = false

    private fun showHistory() {
        showingHistory = true
        results.removeAllViews()
        val fav = Favorites.load(prefs)
        if (fav.isNotEmpty()) section("즐겨찾기", fav, fav)
        val recent = Recents.load(prefs).filterNot { r -> fav.any(r::same) }
        if (recent.isNotEmpty() || fav.isEmpty()) section("최근 목적지", recent, fav)
    }

    private fun section(title: String, places: List<Place>, fav: List<Place>) {
        results.addView(label("\n$title", 13f, MainActivity.ACCENT))
        for (p in places) results.addView(row(p, fav.any(p::same)))
    }

    // 한 줄: 누르면 안내, 오른쪽 ★/☆로 즐겨찾기 고정·해제
    private fun row(p: Place, pinned: Boolean) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(label("${p.name}\n${p.address}", 16f, MainActivity.TEXT).apply {
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                Recents.remember(prefs, p)
                navigate(p)
            }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(label("", 24f, 0).apply {
            var on = pinned
            fun paint() {
                text = if (on) "★" else "☆"
                setTextColor(if (on) MainActivity.ACCENT else MainActivity.SUB)
                contentDescription = if (on) "즐겨찾기 해제" else "즐겨찾기 고정"
            }
            paint()
            setPadding(dp(16), dp(8), dp(4), dp(8))
            setOnClickListener {
                Favorites.toggle(prefs, p)
                if (!on) Recents.remember(prefs, p) // 검색 결과에서 고정해도 기록에 남게
                on = !on
                if (showingHistory) showHistory() else paint() // 기록 화면이면 바로 위로 올림
            }
        })
    }

    private fun label(s: String, size: Float, color: Int) = TextView(this).apply { text = s; textSize = size; setTextColor(color) }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private val simul get() = intent.getBooleanExtra(EXTRA_SIMUL, false)

    private fun navigate(dest: Place) {
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        val v = KNNaviView(this)
        // SDK 화면이 안내 끝(도착·SDK 종료 버튼)을 알려 주면 화면 닫기 (onDestroy에서 안내 정리)
        v.guideStateDelegate = object : KNNaviView_GuideStateDelegate {
            override fun naviViewGuideState(state: KNGuideState) = HudService.log("폰 안내 화면 상태: $state")
            override fun naviViewGuideEnded() {
                HudService.log("폰 안내 화면: 종료")
                finish()
            }
        }
        naviView = v
        // targetSdk 35(Android 15)는 시스템 바 밑까지 그림 → 아래 바(재탐색·✕)가 홈·뒤로 버튼에 가려 못 누름. 아래만 띄운다
        val bottom = { i: WindowInsetsCompat? -> i?.getInsets(WindowInsetsCompat.Type.systemBars())?.bottom ?: 0 }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(MainActivity.BG)
            addView(v)
            // SDK 아래 바의 ✕·☰가 이 폰(HyperOS)에선 반응이 없다 (모의주행 확인창 '네'도 무반응, 실기기 2026-11-04) → 우리 종료 버튼.
            // 위치는 회전 안내판(약 124dp) 바로 아래 오른쪽
            addView(label("안내 종료", 15f, MainActivity.TEXT).apply {
                background = GradientDrawable().apply { setColor(0xCC202020.toInt()); cornerRadius = dp(20).toFloat() }
                setPadding(dp(16), dp(10), dp(16), dp(10))
                setOnClickListener {
                    AlertDialog.Builder(this@KakaoNaviActivity)
                        .setMessage(if (simul) "모의주행을 끝낼까요?" else "길안내를 끝낼까요?")
                        .setPositiveButton("종료") { _, _ -> finish() } // onDestroy에서 안내 정리·HUD 종료
                        .setNegativeButton("취소", null)
                        .show()
                }
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { topMargin = dp(136); marginEnd = dp(12) })
            // 검색 화면에서 바꿔 끼우면 insets 콜백이 바로 안 올 때가 있다 (실기기: 키보드가 닫힐 때만 옴) → 지금 값으로 먼저 띄움
            setPadding(0, 0, 0, bottom(ViewCompat.getRootWindowInsets(window.decorView)))
            ViewCompat.setOnApplyWindowInsetsListener(this) { c, insets ->
                c.setPadding(0, 0, 0, bottom(insets))
                insets
            }
        })
        KakaoGuidance.view = v
        KakaoGuidance.start(dest, simul) { err -> if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val EXTRA_X = "x" // KATEC
        const val EXTRA_Y = "y"
        const val EXTRA_NAME = "name"
        const val EXTRA_SIMUL = "simul"
    }
}
