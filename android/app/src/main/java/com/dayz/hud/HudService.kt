package com.dayz.hud

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.TrafficStats
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.util.Log
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
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
        openLog(dumpDir(this))
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
        // 알림 리스너가 허용돼 있어도 재설치 뒤엔 안 붙어 있을 수 있음 (네이버 안내·카톡·문자 못 받음). 허용 안 됐으면 무시됨
        runCatching { NotificationListenerService.requestRebind(ComponentName(this, NavNotificationListener::class.java)) }
        // TMAP 인증은 하루 한도(약 20건)가 있다 → 기본 내비가 TMAP일 때만 자동 연동. 그 외엔 메인 화면 "TMAP 연동" 버튼으로
        if (MainActivity.navApp(getSharedPreferences("hud", MODE_PRIVATE)).second == MainActivity.TMAP) tmap.start()
        main.removeCallbacks(dataWatch)
        main.postDelayed(dataWatch, 60_000)
        return START_STICKY
    }

    // 데이터 감시: 11/18 밤 차 안 쓰는 동안 18시간에 5.8GB를 올려 보냄 (원인 SDK 미확정, 로그는 재시작으로 사라짐)
    // 1분에 1MB 넘게 쓰면 그때 상태와 소켓에 쓰는 중인 스레드 스택을 dump/data.log에 남김 (adb pull로 가져감)
    private var lastTx = -1L
    private var lastRx = -1L
    private val dataWatch = object : Runnable {
        override fun run() {
            val uid = android.os.Process.myUid()
            val tx = TrafficStats.getUidTxBytes(uid)
            val rx = TrafficStats.getUidRxBytes(uid)
            if (lastTx >= 0 && tx - lastTx + rx - lastRx > 1_000_000) {
                val msg = "데이터 많음: 1분 보내기 ${(tx - lastTx) / 1024}KB 받기 ${(rx - lastRx) / 1024}KB 안내=${KakaoGuidance.state != null} TMAP=${tmap.state}"
                log(msg)
                runCatching {
                    val busy = Thread.getAllStackTraces().filter { (_, st) -> st.any { "Socket" in it.className || "okhttp" in it.className || "ssl" in it.className.lowercase() } }
                    File(dumpDir(this@HudService), "data.log").appendText(
                        "${java.util.Date()} $msg\n" + busy.entries.joinToString("") { (t, st) -> "  [${t.name}]\n" + st.take(25).joinToString("") { "    $it\n" } },
                    )
                }
            }
            lastTx = tx
            lastRx = rx
            main.postDelayed(this, 60_000)
        }
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
            // 길안내가 없으면(아무것도 안 보냈거나 마지막이 안내 종료) TMAP 무안내처럼 종료 + 속도만. 바뀔 때만
            val last = kivic.lastTbt
            if (kivic.ready && last?.connected != true && last?.speed != speedKmh) kivic.sendTbt(TmapMapping.ENDED.copy(speed = speedKmh))
        }
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
        @Deprecated("API 29")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    override fun onDestroy() {
        main.removeCallbacks(dataWatch)
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

        // 주행 로그 파일: dump/drive-날짜.log (logcat은 금방 지워져 지난 주행을 못 봄). 기록 내보내기 zip에 같이 들어감
        @Volatile var logDir: File? = null
        private val logIo = Executors.newSingleThreadExecutor()
        private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        private const val KEEP_DAYS = 14

        // 앱 시작 때 한 번: 폴더 정하고 오래된 주행 로그 지움
        fun openLog(dir: File) {
            if (logDir != null) return
            logDir = dir
            logIo.execute {
                val old = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
                dir.listFiles { f -> f.name.startsWith("drive-") && f.lastModified() < old }?.forEach { it.delete() }
            }
        }

        fun log(msg: String) {
            val at = LocalDateTime.now()
            logDir?.let { d -> logIo.execute { runCatching { File(d, "drive-${DAY.format(at)}.log").appendText("${TIME.format(at)} $msg\n") } } }
            main.post {
                Log.i("DayzHUD", msg) // PC에서 adb logcat -s DayzHUD 로 확인
                logLines.addFirst(msg)
                while (logLines.size > 40) logLines.removeLast()
                onLog?.invoke()
            }
        }
    }
}
