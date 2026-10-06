package io.mmirror

import android.content.Context

enum class ResolutionPreset(
    val id: String,
    val title: String,
    val description: String,
    val width: Int,
    val height: Int,
    val density: Int
) {
    PHONE_720P(
        id = "phone_720p",
        title = "📱 스마트폰 720p 고화질 최적화",
        description = "스마트폰 원본 비율 100% 유지 · 720p HD 초저지연 및 끊김 방지 최적화 (여백 0%)",
        width = 0,
        height = 720,
        density = 0
    );

    /**
     * 스마트폰 고유 종횡비를 100% 보존하면서 짧은 축을 720p로 정규화합니다.
     * H.264 하드웨어 인코더(MediaCodec) 규격에 맞게 16의 배수로 정렬합니다.
     */
    fun computeEffectiveDimensions(
        context: Context,
        rawWidth: Int,
        rawHeight: Int,
        rawDensity: Int
    ): Triple<Int, Int, Int> {
        val shortSide = minOf(rawWidth, rawHeight)
        val scale = if (shortSide > 720) 720f / shortSide.toFloat() else 1.0f
        var w = ((rawWidth * scale).toInt() / 16) * 16
        var h = ((rawHeight * scale).toInt() / 16) * 16
        w = maxOf(320, w)
        h = maxOf(320, h)
        val densityScale = maxOf(w, h).toFloat() / maxOf(rawWidth, rawHeight).toFloat()
        val effectiveDensity = maxOf(120, (rawDensity * densityScale).toInt())
        return Triple(w, h, effectiveDensity)
    }

    companion object {
        const val PREF_KEY = "pref_resolution_preset"

        val DEFAULT = PHONE_720P

        fun fromId(id: String?): ResolutionPreset {
            return PHONE_720P
        }
    }
}
