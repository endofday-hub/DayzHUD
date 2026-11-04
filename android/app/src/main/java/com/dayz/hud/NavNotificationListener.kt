package com.dayz.hud

import android.app.Notification
import android.app.UiModeManager
import android.content.ComponentName
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

// 패키지명은 Phase 0에서 실기기로 확인. 틀려도 ongoing 알림은 전부 기록되므로 덤프에서 찾을 수 있다.
val TARGETS = setOf(
    "com.skt.tmap.ku",                        // TMAP
    "com.nhn.android.nmap",                   // 네이버 지도
    "com.locnall.KimGiSa",                    // 카카오내비
    "net.daum.android.map",                   // 카카오맵
    "com.google.android.projection.gearhead", // Android Auto
)
const val LOG = "notifications.jsonl"

fun dumpDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "dump").apply { mkdirs() }

// Phase 0: 내비 앱 알림을 통째로 덤프해 파서 설계용 샘플을 모은다.
class NavNotificationListener : NotificationListenerService() {

    private val lastByKey = HashMap<String, String>()
    private var naverKey: String? = null
    private var lastTbt: Tbt? = null
    private val unknownIcons = HashSet<String>()

    // 재설치·프로세스 종료 뒤 허용은 남아도 연결(live)이 안 돌아오는 경우가 있다 (HyperOS) → 끊기면 다시 요청
    override fun onListenerConnected() { live = true }

    override fun onListenerDisconnected() {
        live = false
        requestRebind(ComponentName(this, javaClass))
    }

    companion object {
        @Volatile var live = false // 메인 화면 '알림 접근' 상태 표시용
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == NaverMapping.PKG) naver(sbn)
        message(sbn)
        val n = sbn.notification
        if (sbn.packageName !in TARGETS && (n.flags and Notification.FLAG_ONGOING_EVENT) == 0) return

        val record = JSONObject()
            .put("pkg", sbn.packageName)
            .put("id", sbn.id)
            .put("tag", sbn.tag)
            .put("channel", n.channelId)
            .put("category", n.category)
            .put("flags", n.flags)
            .put("carMode", getSystemService(UiModeManager::class.java).currentModeType == Configuration.UI_MODE_TYPE_CAR)
            .put("smallIcon", dump(n.smallIcon))
            .put("largeIcon", dump(n.getLargeIcon()))
            .put("extras", dump(n.extras)) // AA 턴 알림은 extras["androidx.car.app.EXTENSIONS"] 안에 들어온다
            .put("actions", JSONArray(n.actions.orEmpty().map { it.title?.toString() }))
            .put("contentView", dumpViews(n.contentView))
            .put("bigContentView", dumpViews(n.bigContentView))
            .put("headsUpContentView", dumpViews(n.headsUpContentView))
        val body = record.toString()
        // 내비 앱은 같은 내용을 초 단위로 다시 올린다 → 내용이 바뀐 것만 기록
        if (lastByKey.put(sbn.key, body) == body) return

        // ponytail: 메인 스레드 파일 쓰기. 덤프 전용이라 허용, 본 앱에선 제거
        File(dumpDir(this), LOG).appendText(record.put("time", sbn.postTime).toString() + "\n")
    }

    // 네이버지도 AA 안내 알림 → HUD. 초당 여러 번 같은 내용이 오므로 바뀐 것만 보낸다
    @Suppress("DEPRECATION")
    private fun naver(sbn: StatusBarNotification) {
        val car = sbn.notification.extras.getBundle(NaverMapping.CAR_EXTENSIONS) ?: return
        val distance = car.getCharSequence("content_title")?.toString() ?: return
        val icon = (car.getParcelable<Bitmap>("large_bitmap"))?.let { iconHash(png(it)) }
        if (icon != null && NaverMapping.turnCode(icon) == null && unknownIcons.add(icon)) {
            HudService.log("네이버 새 회전 아이콘 $icon — NaverMapping.TURN에 추가 필요")
        }
        naverKey = sbn.key
        send(NaverMapping.toTbt(distance, car.getCharSequence("content_text")?.toString().orEmpty(), icon))
    }

    // 카톡·문자 → HUD 알림 (카톡 아이콘·문자 아이콘은 HUD가 그림). 같은 대화는 같은 key로 내용만 바뀌어 다시 온다
    private val lastMsg = HashMap<String, String>()

    private fun message(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_ONGOING_EVENT) != 0) return
        val kakao = sbn.packageName == "com.kakao.talk"
        if (!kakao && sbn.packageName != Telephony.Sms.getDefaultSmsPackage(this)) return
        val s = HudSettings.load(getSharedPreferences("hud", MODE_PRIVATE))
        if (!(if (kakao) s.kakaoTalk else s.sms)) return
        val who = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (lastMsg.put(sbn.key, text) == text) return
        val hud = HudService.instance ?: return
        HudService.log("HUD 알림: ${if (kakao) "카톡" else "문자"} $who") // 내용은 로그에 안 남김
        // 원본 앱과 같게: 카톡은 "보낸 사람 : 내용" 한 줄, 문자는 제목·내용 따로
        hud.kivic.sendFrames(listOf(
            if (kakao) KivicPacket.social(KivicPacket.KAKAO, "", "$who : $text") else KivicPacket.social(KivicPacket.SMS, who, text),
        ))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key != naverKey) return
        naverKey = null
        send(TmapMapping.ENDED) // 안내 종료 → HUD 화면 정리
    }

    private fun send(t: Tbt) {
        val hud = HudService.instance ?: return
        val withSpeed = if (t.connected) t.copy(speed = hud.speed) else t
        if (withSpeed == lastTbt) return
        lastTbt = withSpeed
        hud.kivic.sendTbt(withSpeed)
    }

    @Suppress("DEPRECATION")
    private fun dump(v: Any?): Any {
        if (v == null) return JSONObject.NULL
        return when (v) {
            is Bundle -> JSONObject().also { o ->
                for (k in v.keySet()) o.put(k, runCatching { dump(v.get(k)) }.getOrElse { "ERR $it" })
            }
            is Bitmap -> saveBitmap(v)
            is Icon -> runCatching { v.loadDrawable(this)?.let { saveBitmap(it.toBitmap()) } ?: JSONObject.NULL }
                .getOrElse { "ERR $it" }
            is Array<*> -> JSONArray(v.map { dump(it) })
            is Collection<*> -> JSONArray(v.map { dump(it) })
            is Number, is Boolean -> v
            is CharSequence -> v.toString()
            else -> "${v.javaClass.name}: $v"
        }
    }

    // 커스텀 레이아웃 알림은 extras에 텍스트가 없을 수 있다 → 실제로 inflate해서 뷰 트리를 훑는다
    private fun dumpViews(rv: RemoteViews?): Any {
        if (rv == null) return JSONObject.NULL
        return runCatching { JSONArray().also { walk(rv.apply(this, FrameLayout(this)), it) } }
            .getOrElse { "ERR $it" }
    }

    private fun walk(v: View, out: JSONArray) {
        val id = runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("?") +
            if (v.visibility != View.VISIBLE) "(hidden)" else ""
        when (v) {
            is TextView -> if (v.text.isNotEmpty()) out.put("$id text:${v.text}")
            is ImageView -> v.drawable?.let { out.put("$id ${saveBitmap(it.toBitmap())}") }
            is ViewGroup -> for (i in 0 until v.childCount) walk(v.getChildAt(i), out)
        }
    }

    // 같은 아이콘 = 같은 파일명 → 이 해시가 회전 아이콘 매핑 테이블의 키가 된다
    private fun saveBitmap(b: Bitmap): String {
        val png = png(b)
        val name = iconHash(png) + ".png"
        val f = File(dumpDir(this), "icons").apply { mkdirs() }.resolve(name)
        if (!f.exists()) f.writeBytes(png)
        return "icon:$name"
    }

    private fun png(b: Bitmap) = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    // NaverMapping.TURN의 키. 덤프 파일명과 같은 값이어야 한다
    private fun iconHash(png: ByteArray) =
        MessageDigest.getInstance("SHA-1").digest(png).joinToString("") { "%02x".format(it) }.take(12)

    private fun Drawable.toBitmap(): Bitmap {
        if (this is BitmapDrawable && bitmap != null) return bitmap
        val w = intrinsicWidth.takeIf { it > 0 } ?: 96
        val h = intrinsicHeight.takeIf { it > 0 } ?: 96
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
            setBounds(0, 0, w, h)
            draw(Canvas(it))
        }
    }
}
