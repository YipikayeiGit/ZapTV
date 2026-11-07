package com.zap.iptv

import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecList

/** Qué formatos de vídeo sabe decodificar este dispositivo. */
object Codecs {
    private val avcHigh10: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/avc", ignoreCase = true) } &&
                    info.getCapabilitiesForType("video/avc").profileLevels.any { it.profile == CodecProfileLevel.AVCProfileHigh10 }
            }
        }.getOrDefault(false)
    }

    /**
     * Si el vídeo no se puede reproducir en este dispositivo, el motivo; si no, null.
     * El caso típico es el anime en H.264 de 10 bits (Hi10P), que casi ningún Fire TV soporta.
     */
    fun problem(codec: String?, profile: String?, bits: Int): String? {
        val c = codec?.lowercase() ?: return null
        val tenBitAvc = (c == "h264" || c == "avc" || c == "avc1") &&
            (bits >= 10 || profile?.contains("10") == true)
        if (tenBitAvc && !avcHigh10) {
            return "Este vídeo está en H.264 de 10 bits (Hi10P), un formato muy usado en anime que este " +
                "dispositivo no sabe reproducir. La app de Plex lo convierte en el servidor; Zap no, " +
                "para respetar la calidad original."
        }
        return null
    }
}
