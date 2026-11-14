# mGBA Wear 0.4 – diagnóstico de audio

Esta versión amplía el panel de diagnóstico; no afirma corregir todavía el tono del audio.

Panel:
- `C`: tiempo de `retro_run` + transferencia JNI.
- `D`: tiempo de dibujo.
- `A`: tiempo que el hilo de emulación espera para insertar el bloque en la cola.
- `W`: tiempo de la última escritura bloqueante a `AudioTrack`.
- `U`: underruns acumulados reportados por `AudioTrack`.
- `SR`: frecuencia declarada por el core / frecuencia reportada por AudioTrack.
- `S`: cantidad de shorts de audio recibidos en el último fotograma (estéreo intercalado).
- `Q`: bloques pendientes en la cola de audio.
- `P`: pico absoluto de PCM antes del volumen de salida.

Prueba en el POCO y en el Galaxy Watch 5 Pro con el mismo juego, durante 30 segundos. Captura el panel mientras el juego está en movimiento. El objetivo es comprobar si ambas frecuencias coinciden y si el tiempo de escritura/cola explica la caída de FPS.

Esta versión conserva la página local y el QR para subir ROMs. El proyecto fuente no se ha compilado ni probado en dispositivos desde este entorno.
