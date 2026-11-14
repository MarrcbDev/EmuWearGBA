# mGBA Wear 0.2 – compatibilidad y audio

Cambios frente a la versión anterior:
- Se vuelve a incluir armeabi-v7a además de arm64-v8a. La versión anterior solo empaquetaba arm64-v8a, lo que podía impedir instalarla en un dispositivo Wear OS con entorno de 32 bits.
- versionCode 2 / versionName 0.2 para permitir actualización sobre la versión previa si conserva la misma firma.
- Se elimina -march=armv8-a global, que no debe aplicarse indiscriminadamente al binario armeabi-v7a.
- La cola de audio deja de descartar bloques cuando se llena; aplica back-pressure para evitar saltos de muestras.
- El tamaño de búfer de AudioTrack evita usar un valor negativo de getMinBufferSize.
- Se aplica headroom PCM adicional al 60% antes de AudioTrack.

Importante:
- Este paquete contiene el código fuente y el workflow de GitHub Actions; no es un APK compilado y probado.
- Si la instalación en el reloj sigue fallando, recopila el mensaje exacto de instalación. Por ADB:
  adb install -r app-debug.apk
  y comparte la salida completa.
- Para diagnosticar audio, prueba primero con el altavoz del reloj/teléfono y volumen del sistema al 50%. Si distorsiona igual, el problema no es simplemente el volumen de salida.
