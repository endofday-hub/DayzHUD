package com.dayz.hud

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.Looper
import android.util.Log

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
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        kivic.connect()
        return START_STICKY
    }

    override fun onDestroy() {
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
