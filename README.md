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

## Si algo falla
- Logs: `adb logcat -s mgbawear`.
- Arquitectura del reloj: `adb shell getprop ro.product.cpu.abilist` (los de 32 bits son más lentos).
- Opciones del core (`mgba_frameskip`, `mgba_color_correction`) en `Settings.kt`.
