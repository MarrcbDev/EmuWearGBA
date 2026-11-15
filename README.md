# EmuWearGBA

Emulador de **Game Boy Advance** (y Game Boy / Game Boy Color) para relojes **Wear OS**, probado en un **Galaxy Watch 5 Pro**. Usa el núcleo de [mGBA](https://github.com/mgba-emu/mgba) y una interfaz hecha desde cero para pantallas redondas.

> ## 🤖 Proyecto creado con IA y supervisado por un humano
> Este proyecto fue **escrito con inteligencia artificial** (Claude, de Anthropic) bajo la **dirección y supervisión de una persona**. El humano define qué se quiere construir, prueba cada versión en un reloj real, revisa los resultados y decide los cambios; la IA genera el código, diagnostica los fallos a partir de los registros y propone las correcciones.
> Eso significa que el código **no ha pasado por una auditoría profesional** y puede tener errores. Úsalo bajo tu propia responsabilidad y revisa el código si vas a reutilizarlo.

---

## Qué puedes hacer

- 🎮 **Jugar** juegos de GBA, GB y GBC (`.gba`, `.gb`, `.gbc`) a ~60 fps con audio.
- 👆 **Controles táctiles** pensados para pantalla redonda, con multitouch real (puedes pulsar A+B o dirección+A a la vez).
- 🖼️ **Biblioteca con carátulas**: se descargan solas (si el reloj tiene Wi-Fi con internet) o las subes tú.
- 📲 **Subir juegos desde el teléfono** escaneando un **código QR** o abriendo un enlace local; sin cables ni apps extra.
- 💾 **Guardar y cargar estado** en 3 ranuras por juego, con miniatura y fecha.
- 🔋 **Partida guardada automática** (la de la batería del cartucho, `.sav`): cada ~30 s, al pausar y al salir.
- ⚙️ **Ajustes**: volumen, salto de frames, FPS en pantalla, diagnóstico, colores GBA, tamaño de pantalla, tamaño y opacidad de botones.
- 🌐 **Página web** vistosa para gestionar la biblioteca: subir ROMs, carátulas y partidas, descargar partidas, borrar juegos.
- ⏸️ **Pausa automática** si sales de la app o se apaga la pantalla.

## Instalación

1. Descarga `EmuWearGBA.apk` desde la pestaña **Releases** de este repositorio.
2. En el reloj activa **Opciones de desarrollador → Depuración ADB** y **Depuración por Wi-Fi**.
3. Desde un ordenador (o Termux en el teléfono) con `adb`:

```bash
adb pair IP:PUERTO_DE_EMPAREJAMIENTO      # código de 6 dígitos que muestra el reloj
adb connect IP:PUERTO
adb install -r EmuWearGBA.apk
```

`-r` actualiza sin perder tus juegos ni partidas. Todos los APK se firman con la misma clave (ver [Firma](#firma)), así que se pueden instalar uno encima de otro.

## Primeros pasos

1. Abre **EmuWearGBA** en el reloj y toca **Subir**. Verás un **código QR** y una dirección como `http://192.168.1.50:8080`.
2. En el teléfono (misma red Wi-Fi) escanea el QR o abre la dirección en el navegador.
3. Arrastra o elige tus archivos: ROMs (`.gba`, `.gb`, `.gbc`), carátulas (cualquier imagen con **el mismo nombre** que el juego) y partidas (`.sav`).
4. Vuelve a la lista del reloj: tus juegos aparecen con su carátula. **Toca uno para jugar**; **mantén pulsado** para borrarlo.

> El servidor solo funciona **mientras la app está abierta** y no tiene contraseña: cualquiera en tu misma red podría subir archivos. Úsalo en redes de confianza.

## Controles

| Control | Dónde está |
|---|---|
| Cruceta | abajo a la izquierda |
| A / B | abajo a la derecha |
| L / R | esquinas superiores |
| Select / Start | abajo al centro (`SEL`, `STA`) |
| **Pausa** (`II`) | arriba al centro |
| Botón físico **Back** | abre el menú de pausa |

**Menú de pausa:** Continuar · Guardar estado · Cargar estado · Reiniciar · Ajustes · Salir al menú.

## Ajustes

| Ajuste | Qué hace |
|---|---|
| Volumen | 20 – 100 %. Útil si el parlante del reloj distorsiona |
| Salto de frames | 0 – 3. Si algún juego pesado va lento, sube este valor |
| Mostrar FPS / Diagnóstico | Muestra información sobre el rendimiento en pantalla (ver abajo) |
| Colores GBA | Corrección de color que imita la pantalla original |
| Tamaño de pantalla | Normal o Grande |
| Tamaño y opacidad de botones | Se aplican al instante |

**Lectura del diagnóstico:** `emu` = ms que tarda el core en emular un frame · `dib` = ms en mostrarlo · `esp` = ms que sobran esperando al siguiente frame (cuanto más, mejor) · `aud …Hz` = tasa de audio usada · `/f` = muestras de audio por frame · `perd` = muestras descartadas por segundo (debería ser 0).

## La página web del reloj

Se sirve en el puerto **8080** mientras la app está abierta.

| Método y ruta | Función |
|---|---|
| `GET /` | Página web (arrastrar y soltar, progreso, biblioteca con carátulas) |
| `GET /api/library` | Lista de juegos en JSON |
| `POST /upload?name=Juego.gba` | Sube una ROM (`.gba/.gb/.gbc`) o una partida (`.sav`) |
| `POST /cover?rom=Juego` | Sube la carátula de un juego |
| `GET /cover?name=Juego` | Descarga la carátula |
| `GET /sav?name=Juego` | Descarga la partida guardada |
| `POST /delete?file=Juego.gba` | Borra el juego y su carátula (**conserva** partidas y estados) |
| `POST /fetch-covers` | Pide al reloj buscar carátulas en línea |

## Cómo funciona por dentro

```
┌──────────────── Interfaz (Kotlin + Jetpack Compose para Wear OS) ────────────────┐
│ Inicio · Subir (QR) · Ajustes · Juego (controles + pausa + estados)               │
└───────────────┬───────────────────────────────────────────┬──────────────────────┘
                │ EmuSession (hilo del emulador)            │ RomServer (NanoHTTPD :8080)
                ▼                                           ▼
        JNI (C, mgba_jni.c)                      filesDir/roms · saves · states · covers
                │ dlopen
                ▼
        libmgba_libretro.so  ← núcleo de mGBA compilado en GitHub Actions
```

- **Núcleo:** mGBA como *core libretro*. La capa JNI (`mgba_jni.c`) es un frontend libretro mínimo: carga el core con `dlopen`, le pasa los botones, recoge audio y video, y gestiona las opciones del core.
- **Video:** el core pinta en un framebuffer RGB565 y la capa nativa lo copia directamente a un `Surface` (`SurfaceView`) con `ANativeWindow`. El sistema lo escala a pantalla, así que casi no cuesta CPU.
- **Ritmo:** el hilo del emulador se sincroniza con un reloj a los fps que declara el core (~59,7), no con el audio.
- **Audio:** al iniciar, durante ~0,75 s se **mide cuánto audio produce realmente el core** y se crea el `AudioTrack` con esa tasa. Las muestras pasan por un **anillo de ~100 ms** a un hilo de audio aparte; si el audio se retrasa, se descarta o se rellena con silencio en vez de frenar el juego.
- **Estados:** `retro_serialize` / `retro_unserialize` del core; se guardan en `filesDir/states/` junto a una miniatura JPEG.
- **Carátulas:** si falta, el reloj intenta descargarla de `thumbnails.libretro.com` (solo coincide si el nombre del archivo es el de la base de datos No-Intro, p. ej. `Advance Wars (USA).gba`). Si no, súbela tú desde la web.
- **Ajustes:** `SharedPreferences`; las opciones del core (`mgba_frameskip`, `mgba_color_correction`) se pasan por la interfaz de variables de libretro.
- **Datos:** todo vive en el almacenamiento privado de la app (`filesDir`). Desinstalarla borra todo.

## Compilar

No necesitas instalar nada: **GitHub Actions** lo hace todo (`.github/workflows/build.yml`).

1. Clona mGBA y compila su core libretro con el NDK para `armeabi-v7a` y `arm64-v8a`.
2. Compila la app con Gradle (`assembleRelease`) y la firma.
3. Sube el APK como artefacto del workflow (pestaña **Actions**).

Se ejecuta con cada *push* a `main`/`master`, con **Run workflow**, o al subir una etiqueta `v*` (que además crea un Release).

> mGBA se descarga de su rama principal en cada compilación. Si algún día una versión nueva de mGBA rompe el build, fija un *commit* concreto en el paso "Clonar mGBA".

### Publicar una versión (Release)

```bash
# 1. (opcional) sube versionName / versionCode en app/build.gradle.kts y haz commit
# 2. crea y sube la etiqueta
git tag v1.0.0
git push origin v1.0.0
```

El workflow compila y adjunta `EmuWearGBA.apk` al Release automáticamente. También puedes crearlo desde la web: **Releases → Draft a new release →** escribe una etiqueta nueva (`v1.0.0`) **→ Publish**; el APK se adjunta solo cuando termina el workflow.

### Firma

El APK se firma con una clave **de depuración pública** guardada en `app/debug.keystore.b64`. Sirve para uso personal y para que las actualizaciones se instalen encima; **no es apta para publicar en Google Play**.

## Solución de problemas

| Síntoma | Qué probar |
|---|---|
| No aparece el QR / dirección | Conecta el reloj a Wi-Fi. Si solo usa Bluetooth con el teléfono no tiene IP local |
| El teléfono no abre la página | Misma red Wi-Fi; algunas redes aíslan los dispositivos entre sí (prueba con el punto de acceso del teléfono) |
| Sin carátula | El nombre no coincide con la base de datos; súbela a mano o renombra la ROM |
| Va lento | Activa **Diagnóstico** y mira `emu`; sube el salto de frames; cierra otras apps |
| Audio con cortes | Mira `perd` en el diagnóstico; baja el volumen si el parlante distorsiona |
| Logs | `adb logcat -s mgbawear` |

## Limitaciones conocidas

- Pensado y probado en **Galaxy Watch 5 Pro**; en otros relojes puede variar.
- Los controles tienen forma de GBA; GB/GBC funcionan pero están menos probados.
- No hay *cheats*, multijugador ni *link cable*.
- Las opciones del core se pasan por sus nombres de variable; si una versión futura de mGBA las renombra, el ajuste dejará de hacer efecto.

## Aviso legal

- Este repositorio **no incluye ROMs ni BIOS**. Usa únicamente copias de juegos que **tú posees legalmente**.
- No está afiliado a Nintendo. *Game Boy* y *Game Boy Advance* son marcas de sus propietarios.

## Créditos y licencias

- [mGBA](https://github.com/mgba-emu/mgba) — emulador, licencia **MPL 2.0**.
- [libretro](https://www.libretro.com/) y su servidor de [thumbnails](https://github.com/libretro-thumbnails) — interfaz de cores y carátulas.
- [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) (BSD) — servidor web.
- [ZXing](https://github.com/zxing/zxing) (Apache 2.0) — código QR.
- Jetpack Compose y Wear Compose (Apache 2.0).
- Código de la app generado con IA (Claude, Anthropic) y supervisado por un humano.
