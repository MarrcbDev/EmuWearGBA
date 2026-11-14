// Frontend libretro minimo: carga libmgba_libretro.so con dlopen y expone
// lo necesario a Kotlin (video directo a un Bitmap, audio y entrada).
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <android/bitmap.h>
#include <android/log.h>
#include "libretro.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "mgbawear", __VA_ARGS__)
#define AUD_MAX 32768 // 16384 frames estéreo: margen para cores que entregan audio en bloques grandes

static void *core;
static void (*p_set_env)(retro_environment_t);
static void (*p_set_video)(retro_video_refresh_t);
static void (*p_set_audio)(retro_audio_sample_t);
static void (*p_set_audio_batch)(retro_audio_sample_batch_t);
static void (*p_set_poll)(retro_input_poll_t);
static void (*p_set_input)(retro_input_state_t);
static void (*p_init)(void);
static bool (*p_load_game)(const struct retro_game_info *);
static void (*p_unload_game)(void);
static void (*p_run)(void);
static void (*p_av)(struct retro_system_av_info *);
static void *(*p_mem_data)(unsigned);
static size_t (*p_mem_size)(unsigned);
static size_t (*p_ser_size)(void);
static bool (*p_ser)(void *, size_t);
static bool (*p_unser)(const void *, size_t);
static void (*p_reset)(void);

static char sysdir[512];
static unsigned pixfmt = RETRO_PIXEL_FORMAT_0RGB1555;
static struct retro_system_av_info av;
static void *rom_buf;
static uint8_t *px;
static uint32_t stride, bmp_w, bmp_h;
static int16_t aud[AUD_MAX];
static int aud_n;
static int keys;
static bool frame_drawn;

// Opciones del core (retro_variable) que se fijan desde Kotlin
static struct { char key[64]; char val[64]; } opts[16];
static int nopts;
static volatile bool opts_dirty = true;

static bool env_cb(unsigned cmd, void *data) {
    switch (cmd) {
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        pixfmt = *(const enum retro_pixel_format *)data;
        return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        *(bool *)data = true;
        return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE: {
        struct retro_variable *v = (struct retro_variable *)data;
        for (int i = 0; i < nopts; i++)
            if (!strcmp(opts[i].key, v->key)) { v->value = opts[i].val; return true; }
        return false;
    }
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        *(bool *)data = opts_dirty;
        opts_dirty = false;
        return true;
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        *(const char **)data = sysdir;
        return true;
    default:
        return false;
    }
}

static inline uint32_t e5(uint32_t v) { return (v << 3) | (v >> 2); }

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data || !px) return;
    frame_drawn = true;
    if (w > bmp_w) w = bmp_w;
    if (h > bmp_h) h = bmp_h;
    for (unsigned y = 0; y < h; y++) {
        uint32_t *dst = (uint32_t *)(px + (size_t)y * stride);
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *s = (const uint32_t *)row;
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = s[x];
                dst[x] = 0xFF000000u | ((c & 0xFF) << 16) | (c & 0xFF00) | ((c >> 16) & 0xFF);
            }
        } else if (pixfmt == RETRO_PIXEL_FORMAT_RGB565) {
            const uint16_t *s = (const uint16_t *)row;
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = s[x], r = e5((c >> 11) & 31), g = (((c >> 5) & 63) << 2) | (((c >> 5) & 63) >> 4), b = e5(c & 31);
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        } else { // 0RGB1555
            const uint16_t *s = (const uint16_t *)row;
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = s[x], r = e5((c >> 10) & 31), g = e5((c >> 5) & 31), b = e5(c & 31);
                dst[x] = 0xFF000000u | (b << 16) | (g << 8) | r;
            }
        }
    }
}

static void audio_cb(int16_t l, int16_t r) {
    if (aud_n + 2 <= AUD_MAX) { aud[aud_n++] = l; aud[aud_n++] = r; }
}
static size_t audio_batch_cb(const int16_t *d, size_t frames) {
    size_t n = frames * 2;
    if (aud_n + n > AUD_MAX) n = (AUD_MAX - aud_n) & ~1u;
    memcpy(aud + aud_n, d, n * sizeof(int16_t));
    aud_n += (int)n;
    // libretro espera el número REAL de frames aceptados, no el solicitado.
    return n / 2;
}
static void poll_cb(void) {}
static int16_t input_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || id > 15) return 0;
    return (keys >> id) & 1;
}

#define SYM(n) do { p_##n = dlsym(core, "retro_" #n); if (!p_##n) { LOGE("falta retro_" #n); return JNI_FALSE; } } while (0)

JNIEXPORT jboolean JNICALL
Java_com_marrdev_mgbawear_Native_loadCore(JNIEnv *env, jclass c, jstring dir) {
    const char *d = (*env)->GetStringUTFChars(env, dir, NULL);
    snprintf(sysdir, sizeof sysdir, "%s", d);
    (*env)->ReleaseStringUTFChars(env, dir, d);
    if (core) return JNI_TRUE;
    core = dlopen("libmgba_libretro.so", RTLD_NOW);
    if (!core) { LOGE("dlopen: %s", dlerror()); return JNI_FALSE; }
    *(void **)&p_set_env = dlsym(core, "retro_set_environment");
    *(void **)&p_set_video = dlsym(core, "retro_set_video_refresh");
    *(void **)&p_set_audio = dlsym(core, "retro_set_audio_sample");
    *(void **)&p_set_audio_batch = dlsym(core, "retro_set_audio_sample_batch");
    *(void **)&p_set_poll = dlsym(core, "retro_set_input_poll");
    *(void **)&p_set_input = dlsym(core, "retro_set_input_state");
    *(void **)&p_init = dlsym(core, "retro_init");
    *(void **)&p_load_game = dlsym(core, "retro_load_game");
    *(void **)&p_unload_game = dlsym(core, "retro_unload_game");
    *(void **)&p_run = dlsym(core, "retro_run");
    *(void **)&p_av = dlsym(core, "retro_get_system_av_info");
    *(void **)&p_mem_data = dlsym(core, "retro_get_memory_data");
    *(void **)&p_mem_size = dlsym(core, "retro_get_memory_size");
    *(void **)&p_ser_size = dlsym(core, "retro_serialize_size");
    *(void **)&p_ser = dlsym(core, "retro_serialize");
    *(void **)&p_unser = dlsym(core, "retro_unserialize");
    *(void **)&p_reset = dlsym(core, "retro_reset");
    if (!p_set_env || !p_init || !p_load_game || !p_run || !p_av) { LOGE("simbolos libretro incompletos"); return JNI_FALSE; }
    p_set_env(env_cb);
    p_set_video(video_cb);
    p_set_audio(audio_cb);
    p_set_audio_batch(audio_batch_cb);
    p_set_poll(poll_cb);
    p_set_input(input_cb);
    p_init();
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_marrdev_mgbawear_Native_loadRom(JNIEnv *env, jclass c, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "rb");
    if (!f) { LOGE("no abre %s", p); (*env)->ReleaseStringUTFChars(env, path, p); return JNI_FALSE; }
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    free(rom_buf);
    rom_buf = malloc(size);
    fread(rom_buf, 1, size, f);
    fclose(f);
    struct retro_game_info info = { p, rom_buf, (size_t)size, NULL };
    bool ok = p_load_game(&info);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (ok) p_av(&av);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jdoubleArray JNICALL
Java_com_marrdev_mgbawear_Native_avInfo(JNIEnv *env, jclass c) {
    jdouble v[4] = { av.timing.fps, av.timing.sample_rate, av.geometry.base_width, av.geometry.base_height };
    jdoubleArray a = (*env)->NewDoubleArray(env, 4);
    (*env)->SetDoubleArrayRegion(env, a, 0, 4, v);
    return a;
}

JNIEXPORT jint JNICALL
Java_com_marrdev_mgbawear_Native_runFrame(JNIEnv *env, jclass c, jobject bmp, jshortArray audio, jint k) {
    AndroidBitmapInfo bi;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bmp, &bi) < 0 || AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return 0;
    px = pixels; stride = bi.stride; bmp_w = bi.width; bmp_h = bi.height;
    keys = k;
    aud_n = 0;
    frame_drawn = false;
    p_run();
    px = NULL;
    AndroidBitmap_unlockPixels(env, bmp);
    (*env)->SetShortArrayRegion(env, audio, 0, aud_n, aud);
    return aud_n | (frame_drawn ? 0x10000 : 0);
}

JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_loadSave(JNIEnv *env, jclass c, jstring path) {
    size_t n = p_mem_size ? p_mem_size(RETRO_MEMORY_SAVE_RAM) : 0;
    void *m = n ? p_mem_data(RETRO_MEMORY_SAVE_RAM) : NULL;
    if (!m) return;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "rb");
    if (f) { fread(m, 1, n, f); fclose(f); }
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_writeSave(JNIEnv *env, jclass c, jstring path) {
    size_t n = p_mem_size ? p_mem_size(RETRO_MEMORY_SAVE_RAM) : 0;
    void *m = n ? p_mem_data(RETRO_MEMORY_SAVE_RAM) : NULL;
    if (!m) return;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "wb");
    if (f) { fwrite(m, 1, n, f); fclose(f); }
    (*env)->ReleaseStringUTFChars(env, path, p);
}

JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_unload(JNIEnv *env, jclass c) {
    if (p_unload_game) p_unload_game();
    free(rom_buf);
    rom_buf = NULL;
}

JNIEXPORT jboolean JNICALL
Java_com_marrdev_mgbawear_Native_saveState(JNIEnv *env, jclass c, jstring path) {
    if (!p_ser_size || !p_ser) return JNI_FALSE;
    size_t n = p_ser_size();
    void *b = malloc(n);
    if (!b) return JNI_FALSE;
    jboolean ok = JNI_FALSE;
    if (p_ser(b, n)) {
        const char *p = (*env)->GetStringUTFChars(env, path, NULL);
        FILE *f = fopen(p, "wb");
        if (f) { ok = fwrite(b, 1, n, f) == n; fclose(f); }
        (*env)->ReleaseStringUTFChars(env, path, p);
    }
    free(b);
    return ok;
}

JNIEXPORT jboolean JNICALL
Java_com_marrdev_mgbawear_Native_loadState(JNIEnv *env, jclass c, jstring path) {
    if (!p_unser) return JNI_FALSE;
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    FILE *f = fopen(p, "rb");
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (!f) return JNI_FALSE;
    fseek(f, 0, SEEK_END);
    long n = ftell(f);
    fseek(f, 0, SEEK_SET);
    void *b = malloc(n);
    jboolean ok = JNI_FALSE;
    if (b && fread(b, 1, n, f) == (size_t)n) ok = p_unser(b, n);
    fclose(f);
    free(b);
    return ok;
}

JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_reset(JNIEnv *env, jclass c) {
    if (p_reset) p_reset();
}

JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_setOption(JNIEnv *env, jclass c, jstring k, jstring v) {
    const char *ks = (*env)->GetStringUTFChars(env, k, NULL);
    const char *vs = (*env)->GetStringUTFChars(env, v, NULL);
    int i;
    for (i = 0; i < nopts; i++) if (!strcmp(opts[i].key, ks)) break;
    if (i < 16) {
        if (i == nopts) { snprintf(opts[i].key, sizeof opts[i].key, "%s", ks); nopts++; }
        snprintf(opts[i].val, sizeof opts[i].val, "%s", vs);
        opts_dirty = true;
    }
    (*env)->ReleaseStringUTFChars(env, k, ks);
    (*env)->ReleaseStringUTFChars(env, v, vs);
}
