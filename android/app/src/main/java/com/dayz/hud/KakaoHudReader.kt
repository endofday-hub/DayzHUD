package com.dayz.hud

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File

// 카카오내비 HUD 모드 화면(폰 단독)을 읽어 HUD로 보낸다. 설정 > 접근성에서 켜야 동작.
// 이벤트는 카카오내비 것만 오고(res/xml/kakao_reader.xml), 이벤트가 오면 1초마다 화면을 읽는다.
// 신호 대기처럼 화면이 안 바뀌어도 계속 보내려고 이벤트 대신 주기적으로 읽음. 3번 연속 못 읽으면 안내 종료
class KakaoHudReader : AccessibilityService() {

    private val main = Handler(Looper.getMainLooper())
    private var polling = false
    private var misses = 0
    private var showing = false // HUD에 카카오 안내를 띄운 상태
    private var last: KakaoMapping.Screen? = null
    private var lastSent: Tbt? = null
    private var turn: Int? = null
    private var next: Int? = null
    private var shotAt = 0L
    private val unknown = HashSet<String>()

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (polling) return
        polling = true
        main.post(poll)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        main.removeCallbacks(poll)
        super.onDestroy()
    }

    private val poll: Runnable = object : Runnable {
        override fun run() {
            val s = read()
            if (s != null) {
                misses = 0
                // 새 구간(회전거리가 늘어남)이거나 3초 지났으면 화살표를 다시 본다
                val prev = last?.tbt?.turnDistance ?: 0
                if (s.tbt.turnDistance > prev + 10 || SystemClock.elapsedRealtime() - shotAt > 3_000) shoot(s)
                last = s
                send(s)
            } else if (++misses >= 3) {
                if (showing) HudService.log("카카오 HUD 화면 끊김 → 안내 종료")
                if (showing) sendTbt(TmapMapping.ENDED)
                showing = false
                polling = false
                misses = 0
                last = null
                return
            }
            main.postDelayed(this, 1_000)
        }
    }

    private fun read(): KakaoMapping.Screen? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName != KakaoMapping.PKG) return null
        val nodes = ArrayList<KakaoMapping.Node>()
        walk(root, nodes)
        return KakaoMapping.read(nodes)
    }

    private fun walk(n: AccessibilityNodeInfo, out: MutableList<KakaoMapping.Node>) {
        val r = Rect().also(n::getBoundsInScreen)
        out += KakaoMapping.Node(
            n.text?.toString().orEmpty(),
            n.contentDescription?.toString().orEmpty(),
            listOf(r.left, r.top, r.right, r.bottom),
        )
        for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, out) }
    }

    private fun send(s: KakaoMapping.Screen) {
        showing = true
        sendTbt(s.tbt.copy(turnCode = turn ?: 0, nextTurnCode = next ?: 0))
    }

    private fun sendTbt(t: Tbt) {
        if (t == lastSent) return
        lastSent = t
        HudService.instance?.kivic?.sendTbt(t)
    }

    // 화면을 찍어 화살표 부분만 잘라 모양으로 회전 종류를 가린다 (Android 11+)
    private fun shoot(s: KakaoMapping.Screen) {
        if (Build.VERSION.SDK_INT < 30 || s.icon == null) return
        shotAt = SystemClock.elapsedRealtime()
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(r: ScreenshotResult) {
                val hw = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)
                val shot = hw?.copy(Bitmap.Config.ARGB_8888, false)
                hw?.recycle()
                r.hardwareBuffer.close()
                if (shot == null) return
                turn = classify(shot, s.icon)
                next = s.nextIcon?.let { classify(shot, it) }
                shot.recycle()
                last?.let(::send) // 화살표가 바뀌었으면 바로 반영
            }

            override fun onFailure(code: Int) = HudService.log("카카오 화면 캡처 실패 ($code)")
        })
    }

    private fun classify(shot: Bitmap, icon: KakaoMapping.Node): Int? {
        val (l, t, r, b) = icon.box
        if (l < 0 || t < 0 || r > shot.width || b > shot.height) return null
        val w = r - l
        val h = b - t
        val px = IntArray(w * h).also { shot.getPixels(it, 0, w, l, t, w, h) }
        val sig = KakaoMapping.signature(px, w, h)
        val code = KakaoMapping.turnCode(sig)
        if (code == null && unknown.add(sig)) {
            HudService.log("카카오 새 회전 모양 $sig — KakaoMapping.TURN에 추가 필요")
            // 어떤 화살표였는지 눈으로 확인하려고 잘라서 저장 (Download 내보내기 zip에 포함)
            val dir = File(dumpDir(this), "kakao").apply { mkdirs() }
            File(dir, "$sig.png").outputStream().use {
                Bitmap.createBitmap(shot, l, t, w, h).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
        return code
    }
}
