# EmuWearGBA (Wear OS)

Emulador de Game Boy / GBA para Galaxy Watch 5 Pro, basado en el core libretro de mGBA.

## Qué hace
- **Biblioteca con carátulas**: se buscan solas en línea (necesita Wi-Fi con internet) o las subes tú desde la web.
- **Guardar/cargar estado** en 3 ranuras por juego, con miniatura (menú de pausa: botón `II` arriba o botón Back).
- **Ajustes**: volumen, salto de frames, FPS en pantalla, colores GBA, tamaño de pantalla, tamaño y opacidad de botones.
- **Página web en el reloj** (puerto 8080): sube ROMs (.gba .gb .gbc), carátulas (imágenes con el mismo nombre) y partidas (.sav); descarga partidas, borra juegos.
- Partidas (.sav) automáticas cada ~30 s, al pausar y al salir.

## Compilar
1. Sube el proyecto a GitHub (rama `main` o `master`).
2. Pestaña Actions → "build-apk" → descarga el artefacto `mgba-wear-debug`.

## Instalar
    adb pair IP:PUERTO_DE_EMPAREJAMIENTO
    adb connect IP:PUERTO
    adb install -r app-debug.apk

Importante: este proyecto ahora usa una firma fija (`app/debug.keystore.b64`). La PRIMERA vez que lo instales
tienes que desinstalar la versión anterior (`adb uninstall com.marrdev.mgbawear`; se borran ROMs y partidas).
Desde ahí, `adb install -r` actualiza sin perder nada.

## Ajustes de rendimiento incluidos
- El puente JNI se compila con optimización nativa `-O3`, incluso en el APK debug.
- El hilo del emulador usa prioridad de audio y un búfer de salida más amplio para reducir cortes.
- El callback de audio informa al core cuántas muestras aceptó realmente, evitando declarar como procesadas muestras descartadas.

## Si algo falla
- Logs: `adb logcat -s mgbawear`.
- Arquitectura del reloj: `adb shell getprop ro.product.cpu.abilist` (los de 32 bits son más lentos).
- Opciones del core (`mgba_frameskip`, `mgba_color_correction`) en `Settings.kt`.


## Ajustes de rendimiento (Galaxy Watch 5 Pro)

- El APK se compila únicamente para `arm64-v8a`, arquitectura del Galaxy Watch 5 Pro.
- El hilo de emulación se sincroniza con los FPS que informa el core mGBA.
- La reproducción de audio usa un hilo independiente y una cola acotada para que las escrituras de `AudioTrack` no bloqueen `retro_run`.
- Se aplica atenuación digital al PCM para reducir clipping audible.
- En **Subir juegos** se muestra un QR que abre `http://IP_DEL_RELOJ:8080`; el teléfono debe estar en la misma red Wi-Fi.

El rendimiento y el nivel de audio deben validarse en el reloj real con el mismo juego y auriculares.
