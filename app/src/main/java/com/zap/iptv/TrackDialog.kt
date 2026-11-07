package com.zap.iptv

import android.app.Activity
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

/** Ventana grande y clara para elegir audio o subtítulos (en lugar del menú pequeño del reproductor). */
@OptIn(UnstableApi::class)
object TrackDialog {
    private val SPANISH = Locale("es")

    fun show(activity: Activity, player: ExoPlayer, type: Int) {
        val groups = player.currentTracks.groups.filter { it.type == type }
        val options = ArrayList<Pair<String, (() -> Unit)>>()
        var checked = -1

        if (type == C.TRACK_TYPE_TEXT) {
            val off = player.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_TEXT) ||
                groups.none { it.isSelected }
            if (off) checked = 0
            options += "Desactivados" to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
        }

        for (g in groups) for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            if (g.isTrackSelected(i) && checked < 0) checked = options.size
            options += name(f, type) to {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, i))
                    .build()
            }
        }

        val title = if (type == C.TRACK_TYPE_AUDIO) "Audio" else "Subtítulos"
        if (options.isEmpty() || (type == C.TRACK_TYPE_TEXT && options.size == 1)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle(title)
                .setMessage(if (type == C.TRACK_TYPE_AUDIO) "Este vídeo no tiene otras pistas de audio." else "Este vídeo no tiene subtítulos.")
                .setPositiveButton("Cerrar", null)
                .show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setSingleChoiceItems(options.map { it.first }.toTypedArray(), checked) { d, which ->
                options[which].second()
                d.dismiss()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** "Español · E-AC3 5.1", "Inglés · SRT · Forzados"… */
    private fun name(f: Format, type: Int): String {
        val code = f.language?.takeIf { it.isNotBlank() && it != "und" }
        val language = code?.let {
            Locale.forLanguageTag(it).getDisplayLanguage(SPANISH)
                .takeIf { n -> n.isNotBlank() && !n.equals(it, ignoreCase = true) }
                ?.replaceFirstChar { c -> c.uppercase() }
        }
        val label = f.label?.takeIf { it.isNotBlank() }
        val parts = ArrayList<String>()
        parts += language ?: label ?: "Pista ${f.id ?: ""}".trim()
        if (language != null && label != null && !label.equals(language, ignoreCase = true)) parts += label
        codec(f.sampleMimeType)?.let { parts += it }
        if (type == C.TRACK_TYPE_AUDIO) channels(f.channelCount)?.let { parts += it }
        if (f.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forzados"
        return parts.joinToString(" · ")
    }

    private fun codec(mime: String?): String? = when (mime) {
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC3"
        MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> "E-AC3"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "ASS"
        MimeTypes.TEXT_VTT -> "VTT"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        else -> null
    }

    private fun channels(n: Int): String? = when (n) {
        1 -> "Mono"
        2 -> "Estéreo"
        6 -> "5.1"
        8 -> "7.1"
        Format.NO_VALUE -> null
        else -> "$n canales"
    }
}
