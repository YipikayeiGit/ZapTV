# Zap · TV, películas y series en una sola app

App Android (móvil, Fire TV y Android TV) que junta tu lista IPTV y tu Plex bajo los mismos apartados.

## Apartados
- **TV en directo**: canales de la lista IPTV, con grupos y búsqueda. Desliza arriba/abajo (o flechas del mando, CH+/CH−) para cambiar de canal.
- **Películas** y **Series**: todo lo de Plex y lo de la lista IPTV mezclado. Si un título está en las dos fuentes aparece una sola vez con la etiqueta `Plex · IPTV`, y al abrirlo eliges cuál reproducir. Plex sale primero, con resolución, idiomas y subtítulos.
- En las series se juntan temporadas y capítulos de ambas fuentes: si a Plex le falta un capítulo, se puede ver desde la lista.
- Filtro **Todo / Plex / IPTV** y búsqueda (sin importar tildes).
- Las pelis y capítulos **continúan donde lo dejaste**.

## Fuentes (botón ⚙)
- **Lista IPTV**: si el enlace es del tipo `…/get.php?username=…&password=…` la app usa el catálogo Xtream completo (canales, pelis, series con temporadas y carátulas). Cualquier otra lista M3U también funciona.
  - En la tele aparece un **QR**: escanéalo con el móvil (misma wifi), pega el enlace o elige el archivo y pulsa *Enviar a la tele*. No hace falta nada más.
  - En el móvil también puedes usar *Compartir → Zap* o abrir un `.m3u`.
- **Plex**: la tele muestra un código; entra en **plex.tv/link** desde el móvil y escríbelo. Verás todos los servidores compartidos con tu cuenta.

## Notas sobre Plex
- El dueño del servidor tiene que compartir sus bibliotecas con tu cuenta de Plex.
- Ver un servidor que no está en tu casa puede requerir Plex Pass (del dueño) o el pase de visionado remoto (tuyo).
- Si la tele no puede reproducir un archivo (formato de audio/vídeo no compatible), la app pide a Plex que lo convierta automáticamente.

## Conseguir la APK (GitHub, sin instalar nada)
1. Crea un repositorio en github.com (puede ser privado).
2. Sube **el contenido** de esta carpeta, incluida `.github`.
3. GitHub compila solo (pestaña **Actions**, unos 5 minutos).
4. Descarga `Zap.apk` desde **Releases**.

En Fire TV: instala *Downloader*, permite "apps de origen desconocido" para ella y abre el enlace de la APK.

## Compilar en tu ordenador
Abre la carpeta con Android Studio → *Build APK(s)*, o `./gradlew assembleRelease`.

## Más
- Firma: `app/keystore/zap.jks` está cifrada. Su contraseña va en el secret `KEYSTORE_PASSWORD` del repositorio (Settings → Secrets and variables → Actions). Sin ella GitHub no compila.
- Android 7.0 o superior.
