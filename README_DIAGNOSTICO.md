# mGBA Wear 0.3 – build de diagnóstico

Este build NO pretende corregir todavía el audio ni prometer 60 FPS. Añade mediciones para identificar el cuello de botella en el Galaxy Watch 5 Pro.

En la superposición de FPS aparecen:
- `C`: milisegundos que tarda `Native.runFrame` (core + conversión JNI).
- `D`: milisegundos del dibujo a `SurfaceHolder`.
- `A`: milisegundos que el hilo de emulación estuvo bloqueado esperando espacio en la cola de audio.
- `U`: underruns reportados por `AudioTrack` (si aumenta, el consumidor de audio no está recibiendo datos a tiempo).
- `P`: pico absoluto de muestra PCM después de la atenuación configurada en el core.

Cómo usarlo:
1. Compila e instala en el reloj con GitHub Actions.
2. Ejecuta el mismo juego y espera 30–60 segundos.
3. Toma una captura donde se vean FPS y métricas.
4. Para el audio, indica si el defecto es crujido/cortes, sonido metálico o distorsión constante.

No se ha compilado ni probado en hardware en este entorno. Se conserva el código de la versión 0.2, incluidos los cambios de compatibilidad y el QR. Se incrementa `versionCode` a 3 para permitir actualizarla si la firma coincide.
