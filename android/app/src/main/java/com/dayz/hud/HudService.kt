package com.dayz.hud

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import kotlin.math.roundToInt

// HUD 연결과 TMAP 수신을 화면과 분리해 유지한다 (화면 회전·앱 전환·화면 꺼짐에도 끊기지 않게).
// 화면은 instance로 직접 접근 — 같은 프로세스라 바인더 불필요
class HudService : Service() {
    lateinit var kivic: KivicLink
        private set
    lateinit var tmap: TmapSource
        private set

    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("hud", MODE_PRIVATE)
        kivic = KivicLink(this, ::log) { kivic.sendFrames(KivicPacket.settings(HudSettings.load(prefs))) }
        tmap = TmapSource(this, ::log) { kivic.sendTbt(it) }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "HUD 연결", NotificationManager.IMPORTANCE_LOW))
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("DayzHUD")
            .setContentText("키빅 HUD 연결 유지 중")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        // 위치 권한이 없으면 location 타입으로 시작할 수 없다 (Android 14+) → 그때는 속도 없이 연결만
        val gpsOk = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        startForeground(
            1, n,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                if (gpsOk) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0,
        )
        if (gpsOk) {
            getSystemService(LocationManager::class.java)
                .requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 0f, gps, Looper.getMainLooper())
        } else log("위치 권한 없음 — GPS 속도 미표시")
        kivic.connect()
        // TMAP 인증은 하루 한도(약 20건)가 있다 → 기본 내비가 TMAP일 때만 자동 연동. 그 외엔 메인 화면 "TMAP 연동" 버튼으로
        if (MainActivity.NAV_APPS.getOrNull(getSharedPreferences("hud", MODE_PRIVATE).getInt("navApp", 0))?.second == MainActivity.TMAP) tmap.start()
        return START_STICKY
    }

    // 네이버 알림엔 속도가 없어 폰 GPS 속도를 쓴다. 3초 넘게 신호 없으면(터널 등) 0
    private var speedKmh = 0
    private var speedAt = 0L
    val speed get() = if (SystemClock.elapsedRealtime() - speedAt < 3_000) speedKmh else 0

    // minSdk 29에선 LocationListener 나머지 메서드가 default가 아니라 전부 구현
    private val gps = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (!l.hasSpeed()) return
            speedKmh = (l.speed * 3.6f).roundToInt()
            speedAt = SystemClock.elapsedRealtime()
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onDestroy() {
        getSystemService(LocationManager::class.java).removeUpdates(gps)
        instance = null
        tmap.stop()
        kivic.close()
        log("연결 종료")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?) = null

    companion object {
        private const val CHANNEL = "hud"
        private val main = Handler(Looper.getMainLooper())

        var instance: HudService? = null
            private set

        // 화면이 다시 만들어져도 이어서 보이도록 로그는 여기 보관
        val logLines = ArrayDeque<String>()
        var onLog: (() -> Unit)? = null

        fun log(msg: String) {
            main.post {
                Log.i("DayzHUD", msg) // PC에서 adb logcat -s DayzHUD 로 확인
                logLines.addFirst(msg)
                while (logLines.size > 40) logLines.removeLast()
                onLog?.invoke()
            }
        }
    }
}
