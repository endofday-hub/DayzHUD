package com.dayz.hud

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.tmapmobility.tmap.tmapsdk.edc.EDCConst
import com.tmapmobility.tmap.tmapsdk.edc.TmapEDCSDK

// TMAP 앱의 실시간 안내(RGData)를 EDC SDK로 받아 Tbt로 넘긴다.
// 무료 한도 일 20건(인증 기준으로 추정) → 초기화는 사용자가 누를 때 한 번만, 자동 재시도 없음
class TmapSource(
    private val ctx: Context,
    private val log: (String) -> Unit,
    private val onTbt: (Tbt) -> Unit,
) {
    private var keysLogged = false
    private var last: Tbt? = null
    private var lastAt = 0L
    private val main = Handler(Looper.getMainLooper())

    // 안내를 끝내도 TMAP이 onRouteFinished 없이 RGData만 끊는 경우가 있다 (실기기) → HUD에 마지막 화면이 남음.
    // RGData는 1초마다 오므로 3초 끊기면 종료로 본다 (원본 앱도 TMAP 정보가 없으면 종료 패킷 전송)
    private val dataTimeout = Runnable {
        log("TMAP 데이터 끊김 → 안내 종료")
        emit(TmapMapping.ENDED)
    }

    fun start() {
        if (BuildConfig.TMAP_API_KEY.isEmpty()) return log("android/secrets.properties에 TMAP_API_KEY 없음")
        if (TmapEDCSDK.isInitialized) return register()
        val auth = TmapEDCSDK.Companion.EDCAuthData(
            BuildConfig.TMAP_CLIENT_ID,
            BuildConfig.TMAP_API_KEY,
            ctx.packageName,
            BuildConfig.TMAP_USER_KEY,
            BuildConfig.TMAP_DEVICE_KEY,
        )
        log("TMAP 연동 시작")
        TmapEDCSDK.initialize(ctx.applicationContext, auth, work, false)
    }

    fun stop() {
        main.removeCallbacks(dataTimeout)
        runCatching {
            TmapEDCSDK.unregisterDataCallback()
            TmapEDCSDK.finish()
        }
    }

    private fun register() {
        log("RGData 콜백 등록")
        TmapEDCSDK.registerDataCallback(data)
    }

    private fun emit(t: Tbt) {
        main.removeCallbacks(dataTimeout)
        if (t.connected) main.postDelayed(dataTimeout, 3_000)
        // RGData는 주기적으로 온다 → 바뀌었거나 2초 지났을 때만 HUD로
        val now = SystemClock.elapsedRealtime()
        if (t == last && now - lastAt < 2_000) return
        last = t
        lastAt = now
        onTbt(t)
    }

    private val work = object : TmapEDCSDK.Companion.EDCWorkListener {
        // bindService 직후 동기 호출 → 아직 서비스 미연결이라 SDK가 등록을 조용히 무시함 (실기기 로그). 1초 뒤 다시 등록
        override fun onInitSuccessEDC() {
            main.postDelayed(::register, 1_000)
        }
        override fun onHostAppStarted() = register()
        override fun onFinishedEDC() {
            log("EDC 종료")
            emit(TmapMapping.ENDED)
        }
        override fun onAuthAction(bundle: Bundle) = log("EDC 인증 상태: ${bundle.getInt("authStatus")}")
        override fun onRouteStarted(bundle: Bundle) {
            log("TMAP 안내 시작")
            register() // 등록이 빠졌어도 안내 시작 시점엔 확실히 붙도록
        }
        override fun onRouteFinished(bundle: Bundle) {
            log("TMAP 안내 종료")
            emit(TmapMapping.ENDED)
        }
        override fun onResult(commandState: EDCConst.CommandState, o: Any) = log("EDC $commandState: $o")
        override fun onFail(commandState: EDCConst.CommandState, i: Int, s: String?) =
            log("EDC 실패 $commandState ($i) $s")
    }

    private val data = object : TmapEDCSDK.Companion.TMAPDataListener {
        override fun onReceive(bundle: Bundle?) {
            val m = bundle?.toMap() ?: return
            if (!keysLogged) {
                keysLogged = true
                log("RGData 키: ${m.keys.sorted()}")
            }
            emit(TmapMapping.toTbt(m))
        }

        override fun onEvent(eventData: EDCConst.EventData, bundle: Bundle?) {}
    }

    // 문서상 RGData는 Bundle 그대로이거나 "EDCData" 안에 들어온다 — 둘 다 받는다
    @Suppress("DEPRECATION")
    private fun Bundle.toMap(): Map<String, Any?> {
        val b = (get("EDCData") as? Bundle) ?: this
        return b.keySet().associateWith { b.get(it) }
    }
}
