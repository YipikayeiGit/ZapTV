# El reproductor carga el decodificador FFmpeg por reflexión
-keep class androidx.media3.decoder.ffmpeg.** { *; }
-dontwarn androidx.media3.decoder.ffmpeg.**

# VLC usa JNI: no renombrar sus clases
-keep class org.videolan.libvlc.** { *; }
-dontwarn org.videolan.libvlc.**
