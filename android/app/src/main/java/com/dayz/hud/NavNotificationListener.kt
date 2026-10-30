package com.dayz.hud

import android.app.Notification
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Bundle
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
    "com.google.android.projection.gearhead", // Android Auto
)
const val LOG = "notifications.jsonl"

fun dumpDir(ctx: Context) = File(ctx.getExternalFilesDir(null), "dump").apply { mkdirs() }

// Phase 0: 내비 앱 알림을 통째로 덤프해 파서 설계용 샘플을 모은다.
class NavNotificationListener : NotificationListenerService() {

    private val lastByKey = HashMap<String, String>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
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
        val png = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val name = MessageDigest.getInstance("SHA-1").digest(png)
            .joinToString("") { "%02x".format(it) }.take(12) + ".png"
        val f = File(dumpDir(this), "icons").apply { mkdirs() }.resolve(name)
        if (!f.exists()) f.writeBytes(png)
        return "icon:$name"
    }

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
