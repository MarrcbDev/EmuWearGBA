// Frontend libretro minimo: carga libmgba_libretro.so con dlopen y expone lo necesario a Kotlin.
// El video se guarda en un framebuffer RGB565 propio y se presenta directo a un ANativeWindow.
#include <jni.h>
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include "libretro.h"

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "mgbawear", __VA_ARGS__)
#define AUD_MAX 16384
#define FB_W 240
#define FB_H 160

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

static uint16_t fb[FB_W * FB_H];
static unsigned fb_w = FB_W, fb_h = FB_H;
static ANativeWindow *win;
static unsigned win_w, win_h;

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

static void video_cb(const void *data, unsigned w, unsigned h, size_t pitch) {
    if (!data) return;
    frame_drawn = true;
    if (w > FB_W) w = FB_W;
    if (h > FB_H) h = FB_H;
    fb_w = w; fb_h = h;
    for (unsigned y = 0; y < h; y++) {
        uint16_t *dst = fb + y * FB_W;
        const uint8_t *row = (const uint8_t *)data + y * pitch;
        if (pixfmt == RETRO_PIXEL_FORMAT_RGB565) {
            memcpy(dst, row, w * 2);
        } else if (pixfmt == RETRO_PIXEL_FORMAT_XRGB8888) {
            const uint32_t *src = (const uint32_t *)row;
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = src[x];
                dst[x] = (uint16_t)(((c >> 8) & 0xF800) | ((c >> 5) & 0x07E0) | ((c >> 3) & 0x001F));
            }
        } else { // 0RGB1555
            const uint16_t *src = (const uint16_t *)row;
            for (unsigned x = 0; x < w; x++) {
                uint32_t c = src[x];
                dst[x] = (uint16_t)(((c & 0x7FE0) << 1) | (c & 0x1F));
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
    return frames;
}
static void poll_cb(void) {}
static int16_t input_cb(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || id > 15) return 0;
    return (keys >> id) & 1;
}

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

// Ejecuta un frame. Devuelve (shorts de audio) | 0x10000 si hay un frame nuevo en el framebuffer.
JNIEXPORT jint JNICALL
Java_com_marrdev_mgbawear_Native_runFrame(JNIEnv *env, jclass c, jshortArray audio, jint k) {
    keys = k;
    aud_n = 0;
    frame_drawn = false;
    p_run();
    (*env)->SetShortArrayRegion(env, audio, 0, aud_n, aud);
    return aud_n | (frame_drawn ? 0x10000 : 0);
}

JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_setSurface(JNIEnv *env, jclass c, jobject surface) {
    if (win) { ANativeWindow_release(win); win = NULL; }
    win_w = win_h = 0;
    if (surface) win = ANativeWindow_fromSurface(env, surface);
}

// Copia el framebuffer a la ventana (un memcpy pequeño; el sistema escala al tamaño de la vista).
JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_present(JNIEnv *env, jclass c) {
    if (!win) return;
    if (win_w != fb_w || win_h != fb_h) {
        ANativeWindow_setBuffersGeometry(win, fb_w, fb_h, WINDOW_FORMAT_RGB_565);
        win_w = fb_w; win_h = fb_h;
    }
    ANativeWindow_Buffer b;
    if (ANativeWindow_lock(win, &b, NULL) < 0) return;
    if (b.format == WINDOW_FORMAT_RGB_565) {
        uint16_t *dst = (uint16_t *)b.bits;
        unsigned rows = fb_h < (unsigned)b.height ? fb_h : (unsigned)b.height;
        unsigned cols = fb_w < (unsigned)b.width ? fb_w : (unsigned)b.width;
        for (unsigned y = 0; y < rows; y++) memcpy(dst + (size_t)y * b.stride, fb + y * FB_W, cols * 2);
    }
    ANativeWindow_unlockAndPost(win);
}

// Copia el ultimo frame a un Bitmap RGB_565 (para las miniaturas de los estados).
JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_copyFrame(JNIEnv *env, jclass c, jobject bmp) {
    AndroidBitmapInfo bi;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bmp, &bi) < 0 || bi.format != ANDROID_BITMAP_FORMAT_RGB_565) return;
    if (AndroidBitmap_lockPixels(env, bmp, &pixels) < 0) return;
    unsigned rows = fb_h < bi.height ? fb_h : bi.height;
    unsigned cols = fb_w < bi.width ? fb_w : bi.width;
    for (unsigned y = 0; y < rows; y++) memcpy((uint8_t *)pixels + (size_t)y * bi.stride, fb + y * FB_W, cols * 2);
    AndroidBitmap_unlockPixels(env, bmp);
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

JNIEXPORT void JNICALL
Java_com_marrdev_mgbawear_Native_unload(JNIEnv *env, jclass c) {
    if (p_unload_game) p_unload_game();
    free(rom_buf);
    rom_buf = NULL;
}
