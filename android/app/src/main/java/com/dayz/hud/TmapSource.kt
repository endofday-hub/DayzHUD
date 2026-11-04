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
    // 연결 상태 (메인 화면 표시용). 바뀔 때 로그도 남겨서 화면이 새로 그려진다
    enum class State(val label: String) { OFF("꺼짐"), NO_KEY("키 없음"), CONNECTING("연결 중…"), READY("연결됨 · 대기"), GUIDING("안내 받는 중"), AUTH_FAILED("인증 실패"), FAILED("실패") }
    @Volatile var state = State.OFF
        private set
    private fun set(s: State) { state = s }

    private var keysLogged = false
    private var last: Tbt? = null
    private var lastAt = 0L
    private val main = Handler(Looper.getMainLooper())

    // 안내를 끝내도 TMAP이 onRouteFinished 없이 RGData만 끊는 경우가 있다 (실기기) → HUD에 마지막 화면이 남음.
    // RGData는 1초마다 오므로 3초 끊기면 종료로 본다 (원본 앱도 TMAP 정보가 없으면 종료 패킷 전송)
    private val dataTimeout = Runnable {
        set(State.READY)
        log("TMAP 데이터 끊김 → 안내 종료")
        emit(TmapMapping.ENDED)
    }

    fun start() {
        val key = ApiKeys.tmap(ctx.getSharedPreferences("hud", Context.MODE_PRIVATE))
        if (key.isEmpty()) return set(State.NO_KEY).also { log("TMAP API 키 없음 (앱의 API 키 항목에서 입력)") }
        if (TmapEDCSDK.isInitialized) return set(State.READY).also { register() }
        val auth = TmapEDCSDK.Companion.EDCAuthData(
            BuildConfig.TMAP_CLIENT_ID,
            key,
            ctx.packageName,
            BuildConfig.TMAP_USER_KEY,
            BuildConfig.TMAP_DEVICE_KEY,
        )
        set(State.CONNECTING)
        log("TMAP 연동 시작")
        TmapEDCSDK.initialize(ctx.applicationContext, auth, work, false)
    }

    fun stop() {
        set(State.OFF)
        main.removeCallbacks(dataTimeout)
        runCatching {
            TmapEDCSDK.unregisterDataCallback()
            TmapEDCSDK.finish()
        }
    }

    // SDK는 콜백이 이미 저장돼 있으면 TMAP 등록을 건너뛴다 (TmapRemoteService.registerDataCallback: dataCallback != null → 무시).
    // 인증 전에 한 등록이 TMAP에 안 붙으면 그 뒤 등록이 전부 무시돼 RGData가 안 옴 (실기기 2026-11-04) → 매번 해제 후 등록
    private fun register() {
        log("RGData 콜백 등록")
        runCatching { TmapEDCSDK.unregisterDataCallback() }
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
            set(State.READY)
            log("TMAP 연결됨")
            main.postDelayed(::register, 1_000)
        }
        override fun onHostAppStarted() = register()
        override fun onFinishedEDC() {
            set(State.OFF)
            log("EDC 종료")
            emit(TmapMapping.ENDED)
        }
        // authStatus: -1 실패, 0 진행 중, 1 성공 (EDC 문서)
        override fun onAuthAction(bundle: Bundle) {
            val a = bundle.getInt("authStatus")
            if (a == -1) set(State.AUTH_FAILED)
            if (a == 1) register() // 인증이 끝난 뒤의 등록이 확실히 붙도록
            log(if (a == -1) "TMAP 인증 실패 (TMAP API 키 확인 — 서버는 appKey만 검사)" else "EDC 인증 상태: $a")
            // 실패 사유가 authStatus 말고 다른 값으로 오는지 확인용 (키 이름에 key/token 들어간 값은 가림)
            log("EDC 인증 응답: " + bundle.toMap().map { (k, v) -> "$k=" + if (Regex("key|token|secret", RegexOption.IGNORE_CASE).containsMatchIn(k)) "***" else v }.joinToString())
        }
        override fun onRouteStarted(bundle: Bundle) {
            set(State.GUIDING)
            log("TMAP 안내 시작")
            register() // 등록이 빠졌어도 안내 시작 시점엔 확실히 붙도록
        }
        override fun onRouteFinished(bundle: Bundle) {
            set(State.READY)
            log("TMAP 안내 종료")
            emit(TmapMapping.ENDED)
        }
        override fun onResult(commandState: EDCConst.CommandState, o: Any) = log("EDC $commandState: $o")
        override fun onFail(commandState: EDCConst.CommandState, i: Int, s: String?) {
            // TMAP 앱이 안 켜져 있으면 콜백 등록이 TMAP_IS_NOT_RUNNING으로 실패 → 실패가 아니라 대기. TMAP이 켜지면 onHostAppStarted에서 다시 등록
            // (실패로 두면 'TMAP 연동' 버튼이 다시 나오고, 누르면 인증을 또 해서 요금이 나감)
            if (s == "TMAP_IS_NOT_RUNNING") return log("TMAP 앱이 꺼져 있음 → TMAP을 켜면 자동으로 받기 시작")
            if (state != State.AUTH_FAILED) set(State.FAILED) // 인증 실패 뒤 콜백 등록도 실패로 오는데, 원인(인증)을 화면에 남긴다
            log("EDC 실패 $commandState ($i) $s")
        }
    }

    private val data = object : TmapEDCSDK.Companion.TMAPDataListener {
        override fun onReceive(bundle: Bundle?) {
            val m = bundle?.toMap() ?: return
            if (state != State.GUIDING) {
                set(State.GUIDING)
                log("TMAP 안내 데이터 받는 중")
            }
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
