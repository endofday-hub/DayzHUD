package com.dayz.hud

import android.content.SharedPreferences

// Kivic HUD 화면 설정. 원본 앱은 연결할 때마다 전부 다시 보낸다 → 우리도 저장해 두고 연결 시 재전송
// 범위는 원본 앱 SeekBar 기준 (keystone 0~0.1, scale 0~0.2, 밝기 0~255)
data class HudSettings(
    val theme: Int = 0,          // 0 앰버, 1 시안, 2 핑크, 3 옐로, 4 그린
    val navTheme: Int = 0,       // 0 일반, 1 자동차
    val scale: Float = 0f,
    val keystone: Float = 0f,
    val reverse: Boolean = false, // 뒤집힌 가로
    val fullScreen: Boolean = false,
    val showSpeed: Boolean = true,
    val speedGauge: Boolean = false,
    val speedColor: Int = WHITE,
    val clock: Boolean = true, // 재설치하면 설정이 초기화되므로 기본 켬
    val autoBrightness: Boolean = true,
    val brightness: Int = 255,
) {
    fun save(p: SharedPreferences) = p.edit()
        .putInt("theme", theme).putInt("navTheme", navTheme)
        .putFloat("scale", scale).putFloat("keystone", keystone)
        .putBoolean("reverse", reverse).putBoolean("fullScreen", fullScreen)
        .putBoolean("showSpeed", showSpeed).putBoolean("speedGauge", speedGauge)
        .putInt("speedColor", speedColor).putBoolean("clock", clock)
        .putBoolean("autoBrightness", autoBrightness).putInt("brightness", brightness)
        .apply()

    companion object {
        const val WHITE = -1
        const val CYAN = 0xFF00FFFF.toInt()
        const val GREEN = 0xFF00FF00.toInt()

        fun load(p: SharedPreferences) = HudSettings().run {
            HudSettings(
                theme = p.getInt("theme", theme),
                navTheme = p.getInt("navTheme", navTheme),
                scale = p.getFloat("scale", scale),
                keystone = p.getFloat("keystone", keystone),
                reverse = p.getBoolean("reverse", reverse),
                fullScreen = p.getBoolean("fullScreen", fullScreen),
                showSpeed = p.getBoolean("showSpeed", showSpeed),
                speedGauge = p.getBoolean("speedGauge", speedGauge),
                speedColor = p.getInt("speedColor", speedColor),
                clock = p.getBoolean("clock", clock),
                autoBrightness = p.getBoolean("autoBrightness", autoBrightness),
                brightness = p.getInt("brightness", brightness),
            )
        }
    }
}

// API 키: 앱에서 입력한 값(prefs "hud") 우선, 비어 있으면 빌드 때 넣은 secrets.properties 값
object ApiKeys {
    const val TMAP = "tmapApiKey"
    const val KAKAO = "kakaoAppKey"

    fun tmap(p: SharedPreferences) = p.getString(TMAP, null)?.trim()?.ifEmpty { null } ?: BuildConfig.TMAP_API_KEY
    fun kakao(p: SharedPreferences) = p.getString(KAKAO, null)?.trim()?.ifEmpty { null } ?: BuildConfig.KAKAO_APP_KEY
}
