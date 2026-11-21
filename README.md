# GroundScreen: receptor AirPlay para Android TV

GroundScreen es una app para **Xiaomi TV Box** (Mi Box, Mi Box S, TV Stick y cualquier Android TV o Google TV)
que convierte la tele en un receptor **AirPlay**, al estilo de AirScreen. Con ella puedes **duplicar la pantalla
del iPhone o iPad** en la tele, con imagen y sonido, y además usarla como altavoz AirPlay para música.

- Duplicación de pantalla en 1080p a 60 fps (también hay modos 1080p30 y 720p30 para equipos más lentos).
- Decodificación H.264 por hardware (MediaCodec) con muy poca latencia.
- Audio de la duplicación (AAC-ELD) y música por AirPlay (ALAC / AAC).
- Aparece en el menú de apps de Android TV, se maneja con el control remoto y puede arrancar sola al encender el TV Box.
- No pide cuenta, no tiene anuncios y no se conecta a internet: todo pasa dentro de tu red Wi-Fi.

## Instalación en el Xiaomi TV Box

1. Descarga el APK: [`dist/GroundScreen.apk`](dist/GroundScreen.apk). También puedes generarlo tú mismo
   (ver *Compilar*).
2. Pásalo al TV Box con cualquiera de estos métodos:
   - **Send Files to TV**: instala la app en el teléfono Android y en la TV y envía el APK.
   - **Downloader** (app de AFTVnews): en la TV, abre la URL "raw" del APK en GitHub.
   - **Memoria USB**: copia el APK y ábrelo con un explorador de archivos (por ejemplo, *X-plore* o *File Commander*).
   - **adb**: `adb connect <IP-del-TV-Box>` y luego `adb install dist/GroundScreen.apk`.
3. La primera vez, Android te pide permitir *Orígenes desconocidos* (o *Instalar apps desconocidas*)
   para la app con la que abres el APK.

## Uso

1. Abre **GroundScreen** en la TV. Aparece el nombre del receptor (por defecto `GroundScreen TV`).
2. Conecta el iPhone a **la misma red Wi-Fi** que el TV Box.
3. En el iPhone abre el **Centro de control**, toca **Duplicar pantalla** y elige `GroundScreen TV`.

Controles en la TV:

| Botón | Acción |
|---|---|
| **Cambiar nombre** | Cambia el nombre con el que aparece en el iPhone. |
| **Calidad** | Alterna entre 1080p 60 fps, 1080p 30 fps y 720p 30 fps. |
| **Iniciar al encender** | Deja el receptor activo en segundo plano desde que se enciende el TV Box. |
| **Abrir automáticamente** | Solo aparece en Android 10 o posterior. Concede el permiso para que la app se abra sola al duplicar. |
| OK / Menú durante la duplicación | Muestra u oculta el panel. Atrás lo cierra. |

> En **Android 9 o anterior** (Mi Box S original) la app se abre sola cuando el iPhone empieza a duplicar.
> En **Android 10 o posterior** (Xiaomi TV Box S 2.ª gen., Google TV) abre la app antes de duplicar,
> salvo que hayas concedido el permiso de *Abrir automáticamente*.

### Si el iPhone no encuentra la TV

- Verifica que los dos estén en la misma red (no en la red de invitados) y que el router no tenga activado
  el "aislamiento de clientes / AP isolation".
- Si el router tiene dos bandas (2,4 GHz y 5 GHz) con nombres distintos, ponlos en la misma.
- Cierra y vuelve a abrir la app, o usa **Cambiar nombre** para forzar un nuevo anuncio en la red.
- Para un video más fluido usa Wi-Fi de 5 GHz o cable Ethernet en el TV Box, o baja la calidad a 30 fps.

## Limitaciones

- Solo admite **duplicación de pantalla y audio**. El botón AirPlay *dentro* de apps de video
  (YouTube, Netflix, Apple TV+) no envía el video por URL. Para esos casos usa la duplicación de pantalla.
  Algunas apps con DRM muestran la pantalla en negro mientras duplicas.
- Sirve un iPhone a la vez. Si se conecta otro, reemplaza al anterior.
- No tiene código PIN de acceso: cualquier persona en tu Wi-Fi puede duplicar en la TV.

## Cómo funciona

```
iPhone ──mDNS (_airplay._tcp / _raop._tcp)──▶ NsdManager de Android
iPhone ──RTSP :7000 (info, pair-setup/verify, fp-setup, SETUP)──▶ RtspConnection
iPhone ──TCP: H.264 cifrado AES-CTR (FairPlay)──▶ MirrorVideoReceiver ─▶ VideoRenderer (MediaCodec → Surface)
iPhone ──UDP/RTP: audio cifrado AES-CBC──▶ AudioReceiver ─▶ AudioRenderer (MediaCodec / ALAC Java → AudioTrack)
```

| Ruta | Contenido |
|---|---|
| `app/src/main/java/com/groundscreen/tv/` | App Android: actividad de TV, servicio en primer plano, preferencias |
| `.../tv/server/` | Servidor AirPlay: RTSP, canal de video, audio RTP, anuncio mDNS |
| `.../tv/media/` | Decodificación: H.264 por hardware, AAC por MediaCodec, decodificador ALAC en Java |
| `.../tv/util/BinaryPlist.java` | Lector y escritor de plist binario de Apple (sin dependencias) |
| `app/src/main/java/com/github/serezhka/airplay/protocol/` | Emparejamiento y FairPlay, adaptados de [java-airplay](https://github.com/serezhka/java-airplay) (MIT) |
| `tools/jvm-test/` | Pruebas de protocolo de punta a punta en la JVM y prueba del decodificador ALAC contra ffmpeg |

## Compilar

**Con Android Studio o Gradle** (requiere el SDK de Android):

```bash
./gradlew assembleRelease      # → app/build/outputs/apk/release/app-release.apk
```

El workflow `.github/workflows/build.yml` compila el APK y corre las pruebas en cada push. El APK queda como
artefacto descargable en la pestaña *Actions* de GitHub.

**Sin el SDK de Google** (Ubuntu/Debian, con las herramientas Android que trae la distribución):

```bash
sudo apt-get install android-sdk-platform-23 android-sdk-build-tools dalvik-exchange openjdk-17-jdk zip
./tools/build-apk.sh           # → build/GroundScreen.apk
```

**Pruebas:**

```bash
./tools/jvm-test/run.sh        # simula un iPhone: pairing, FairPlay, SETUP, video y audio cifrados
./tools/jvm-test/alac-test.sh  # decodificador ALAC comparado bit a bit con ffmpeg
```

## Licencias

El código de protocolo AirPlay y FairPlay viene de [java-airplay](https://github.com/serezhka/java-airplay)
(© Sergei Fedorov, MIT), ver `LICENSE-java-airplay.txt`. El decodificador ALAC es un port del decodificador de
referencia de Apple (Apache 2.0). Más detalles en `THIRD_PARTY_NOTICES.md`.

AirPlay e iPhone son marcas de Apple Inc. Este proyecto no está afiliado a Apple.
