# mGBA Watch (Wear OS)

App Wear OS (Galaxy Watch 5 Pro) que usa el core libretro de mGBA.

- Controles táctiles multitouch pensados para pantalla redonda (`Layout.kt`).
- Servidor HTTP en el reloj (puerto 8080): abre `http://IP_DEL_RELOJ:8080` en el teléfono (mismo Wi-Fi) y sube `.gba/.gb/.gbc`. Se guardan en `filesDir/roms`.
- Partidas (.sav) en `filesDir/saves`, se guardan cada ~30 s y al salir.
- Botón Back físico = salir al menú.

## Compilar
1. Sube este proyecto a un repo de GitHub (rama `main`).
2. Pestaña Actions → "build-apk" → descarga el artefacto `mgba-wear-debug`.

## Instalar
En el reloj: Opciones de desarrollador → Depuración ADB y Depuración por Wi-Fi.
    adb pair IP:PUERTO_DE_EMPAREJAMIENTO
    adb connect IP:PUERTO
    adb install app-debug.apk

## Si algo falla
- El paso "Compilar el core libretro" es el más probable de necesitar ajustes (opciones de CMake de mGBA).
- Ver arquitectura del reloj: `adb shell getprop ro.product.cpu.abilist`.
- Logs del core/JNI: `adb logcat -s mgbawear`.
