#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <locale.h>
#include <math.h>
#include <android/log.h>
#include "client.h"

// FFmpeg's Android API uses opaque JVM/context pointers.
extern int av_jni_set_java_vm(void *, void *);
extern int av_jni_set_android_app_ctx(void *, void *);

static const char *metrics[] = {"time-pos",
                                "demuxer-cache-duration",
                                "cache-buffering-state",
                                "avsync",
                                "decoder-frame-drop-count",
                                "frame-drop-count",
                                "audio-pts",
                                "volume",
                                "current-tracks/video/id"};
#define METRIC_COUNT (sizeof(metrics) / sizeof(metrics[0]))
#define BUFFERED_METRIC 1
#define AUDIO_PTS_METRIC 6
#define VIDEO_ID_METRIC 8
#define METRIC_EVENT 10
#define DECODER_EVENT (METRIC_EVENT + METRIC_COUNT)

typedef struct {
    mpv_handle *mpv;
    jobject surface;
    int buffering;
    int playing;
    int still_image;
    int ended;
    int fatal;
    double aspect;
    double metrics[METRIC_COUNT];
    int metric_available[METRIC_COUNT];
    char decoder[33];
} Player;

static void failure(JNIEnv *env, const char *message) {
    jclass type = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (type)
        (*env)->ThrowNew(env, type, message);
}

JNIEXPORT void JNICALL Java_com_parmesh_livetv_MpvPlayer_environment(JNIEnv *env, jclass type,
                                                                     jobject context) {
    JavaVM *vm = NULL;
    (*env)->GetJavaVM(env, &vm);
    jobject app = (*env)->NewGlobalRef(env, context);
    if (!app)
        return;
    setlocale(LC_NUMERIC, "C");
    // Kept for the process lifetime: FFmpeg may access this from decoder threads.
    if (av_jni_set_java_vm(vm, NULL) < 0 || av_jni_set_android_app_ctx(app, NULL) < 0) {
        (*env)->DeleteGlobalRef(env, app);
        failure(env, "Cannot initialize Android decoder context");
    }
}

static int option(JNIEnv *env, Player *p, const char *key, const char *value) {
    int result = mpv_set_option_string(p->mpv, key, value);
    if (result < 0) {
        char message[160];
        snprintf(message, sizeof(message), "mpv option %s: %s", key, mpv_error_string(result));
        failure(env, message);
    }
    return result;
}

JNIEXPORT jlong JNICALL Java_com_parmesh_livetv_MpvPlayer_create(JNIEnv *env, jclass type,
                                                                 jobject surface, jstring url,
                                                                 jstring agent, jstring ca) {
    Player *p = calloc(1, sizeof(*p));
    if (!p) {
        failure(env, "Cannot allocate mpv player");
        return 0;
    }
    p->mpv = mpv_create();
    p->surface = (*env)->NewGlobalRef(env, surface);
    if (!p->mpv || !p->surface)
        goto fail;
    // Route nested HLS requests through FFmpeg so the retry policy applies consistently.
    // Retry sleeps total at most 10s; these do not include time spent in network I/O.
    const char *retries =
        "reconnect=1,reconnect_streamed=1,reconnect_on_network_error=1,reconnect_on_http_error=5xx,"
        "reconnect_delay_max=4,reconnect_max_retries=4,reconnect_delay_total_max=10";
    const char *options[][2] = {
        {"curl-enabled", "no"},
        {"config", "no"},
        {"terminal", "no"},
        {"input-default-bindings", "no"},
        {"input-vo-keyboard", "no"},
        {"osc", "no"},
        {"osd-level", "0"},
        {"vo", "mediacodec_embed"},
        {"hwdec", "mediacodec"},
        {"ao", "audiotrack,opensles"},
        {"audio-channels", "stereo"},
        {"idle", "yes"},
        {"keep-open", "no"},
        {"cache", "yes"},
        {"cache-pause", "yes"},
        {"cache-pause-initial", "yes"},
        // A small startup/rebuffer cushion, with bounded read-ahead.
        {"cache-pause-wait", "2.5"},
        {"cache-secs", "5"},
        {"demuxer-max-bytes", "16777216"},
        {"demuxer-max-back-bytes", "0"},
        {"stream-lavf-o", retries},
        {"demuxer-lavf-o", retries},
        // Socket timeout, not the overall startup or rendered-frame deadline.
        {"network-timeout", "60"},
        {"tls-verify", "yes"},
        {"ytdl", "no"},
        {"pause", "yes"},
        {"volume", "0"}};
    for (size_t i = 0; i < sizeof(options) / sizeof(options[0]); i++)
        if (option(env, p, options[i][0], options[i][1]) < 0)
            goto fail;
    const char *strings[] = {"user-agent", "tls-ca-file"};
    jstring values[] = {agent, ca};
    for (int i = 0; i < 2; i++) {
        const char *value = (*env)->GetStringUTFChars(env, values[i], NULL);
        if (!value)
            goto fail;
        int result = option(env, p, strings[i], value);
        (*env)->ReleaseStringUTFChars(env, values[i], value);
        if (result < 0)
            goto fail;
    }
    int64_t wid = (intptr_t)p->surface;
    if (mpv_set_option(p->mpv, "wid", MPV_FORMAT_INT64, &wid) < 0 || mpv_initialize(p->mpv) < 0)
        goto fail;
    mpv_observe_property(p->mpv, 0, "paused-for-cache", MPV_FORMAT_FLAG);
    mpv_observe_property(p->mpv, 1, "video-params/aspect", MPV_FORMAT_DOUBLE);
    // Observe on the worker at initialization; never synchronously query the core
    // from Android's UI thread, including through dumpsys diagnostics.
    for (size_t i = 0; i < METRIC_COUNT; i++)
        mpv_observe_property(p->mpv, METRIC_EVENT + i, metrics[i], MPV_FORMAT_DOUBLE);
    mpv_observe_property(p->mpv, DECODER_EVENT, "hwdec-current", MPV_FORMAT_STRING);
    mpv_observe_property(p->mpv, 2, "current-tracks/video/image", MPV_FORMAT_FLAG);
    const char *path = (*env)->GetStringUTFChars(env, url, NULL);
    if (!path)
        goto fail;
    const char *command[] = {"loadfile", path, NULL};
    int result = mpv_command_async(p->mpv, 0, command);
    (*env)->ReleaseStringUTFChars(env, url, path);
    if (result < 0)
        goto fail;
    p->buffering = 1;
    return (intptr_t)p;
fail:
    if (p->mpv)
        mpv_terminate_destroy(p->mpv);
    if (p->surface)
        (*env)->DeleteGlobalRef(env, p->surface);
    free(p);
    if (!(*env)->ExceptionCheck(env))
        failure(env, "Cannot initialize mpv playback");
    return 0;
}

JNIEXPORT jint JNICALL Java_com_parmesh_livetv_MpvPlayer_poll(JNIEnv *env, jclass type,
                                                              jlong handle) {
    Player *p = (Player *)(intptr_t)handle;
    for (int i = 0; i < 64; i++) {
        mpv_event *event = mpv_wait_event(p->mpv, 0);
        if (event->event_id == MPV_EVENT_NONE)
            break;
        if (event->event_id == MPV_EVENT_PLAYBACK_RESTART)
            p->playing = 1;
        if (event->event_id == MPV_EVENT_PROPERTY_CHANGE) {
            mpv_event_property *property = event->data;
            if (event->reply_userdata == 0 && property->format == MPV_FORMAT_FLAG && property->data)
                p->buffering = *(int *)property->data;
            if (event->reply_userdata == 1 && property->format == MPV_FORMAT_DOUBLE &&
                property->data)
                p->aspect = *(double *)property->data;
            if (event->reply_userdata == 2)
                p->still_image =
                    property->format == MPV_FORMAT_FLAG && property->data && *(int *)property->data;
            if (event->reply_userdata >= METRIC_EVENT && event->reply_userdata < DECODER_EVENT) {
                size_t i = event->reply_userdata - METRIC_EVENT;
                p->metric_available[i] = property->format == MPV_FORMAT_DOUBLE && property->data;
                if (p->metric_available[i])
                    p->metrics[i] = *(double *)property->data;
            }
            if (event->reply_userdata == DECODER_EVENT) {
                const char *decoder = property->format == MPV_FORMAT_STRING && property->data
                                          ? *(char **)property->data
                                          : "";
                snprintf(p->decoder, sizeof(p->decoder), "%s", decoder);
            }
        }
        if (event->event_id == MPV_EVENT_END_FILE) {
            mpv_event_end_file *end = event->data;
            if (end->reason == MPV_END_FILE_REASON_EOF ||
                end->reason == MPV_END_FILE_REASON_ERROR) {
                p->ended = 1;
                p->fatal = end->error == MPV_ERROR_VO_INIT_FAILED ||
                           end->error == MPV_ERROR_AO_INIT_FAILED ||
                           end->error == MPV_ERROR_UNSUPPORTED;
                __android_log_print(ANDROID_LOG_INFO, "TippyMpv",
                                    "Stream ended: reason=%d error=%d", end->reason, end->error);
            }
        }
        if (event->event_id == MPV_EVENT_COMMAND_REPLY && event->error < 0)
            p->ended = 1;
    }
    return p->ended | (p->buffering << 1) | (p->playing << 2) | (p->fatal << 3);
}

JNIEXPORT jdouble JNICALL Java_com_parmesh_livetv_MpvPlayer_aspect(JNIEnv *env, jclass type,
                                                                   jlong handle) {
    return ((Player *)(intptr_t)handle)->aspect;
}

JNIEXPORT jdouble JNICALL Java_com_parmesh_livetv_MpvPlayer_radioPosition(JNIEnv *env, jclass type,
                                                                          jlong handle) {
    Player *p = (Player *)(intptr_t)handle;
    if (p->metric_available[VIDEO_ID_METRIC] && !p->still_image)
        return NAN;
    return p->metric_available[AUDIO_PTS_METRIC] ? p->metrics[AUDIO_PTS_METRIC] : NAN;
}

JNIEXPORT jdouble JNICALL Java_com_parmesh_livetv_MpvPlayer_buffered(JNIEnv *env, jclass type,
                                                                     jlong handle) {
    Player *p = (Player *)(intptr_t)handle;
    return p->metric_available[BUFFERED_METRIC] ? p->metrics[BUFFERED_METRIC] : -1;
}

JNIEXPORT void JNICALL Java_com_parmesh_livetv_MpvPlayer_property(JNIEnv *env, jclass type,
                                                                  jlong handle, jstring name,
                                                                  jstring value) {
    Player *p = (Player *)(intptr_t)handle;
    const char *key = (*env)->GetStringUTFChars(env, name, NULL);
    if (!key)
        return;
    const char *text = (*env)->GetStringUTFChars(env, value, NULL);
    if (text) {
        mpv_set_property_async(p->mpv, 0, key, MPV_FORMAT_STRING, &text);
        (*env)->ReleaseStringUTFChars(env, value, text);
    }
    (*env)->ReleaseStringUTFChars(env, name, key);
}

JNIEXPORT void JNICALL Java_com_parmesh_livetv_MpvPlayer_destroy(JNIEnv *env, jclass type,
                                                                 jlong handle) {
    Player *p = (Player *)(intptr_t)handle;
    // This runs on the slot's worker, after UI polling and commands have stopped.
    mpv_terminate_destroy(p->mpv);
    (*env)->DeleteGlobalRef(env, p->surface);
    free(p);
}

JNIEXPORT jstring JNICALL Java_com_parmesh_livetv_MpvPlayer_snapshot(JNIEnv *env, jclass type,
                                                                     jlong handle) {
    Player *p = (Player *)(intptr_t)handle;
    char text[768];
    size_t offset = 0;
    for (size_t i = 0; i < METRIC_COUNT; i++) {
        if (p->metric_available[i]) {
            int written = snprintf(text + offset, sizeof(text) - offset, "%s=%.3f ", metrics[i],
                                   p->metrics[i]);
            if (written < 0 || (size_t)written >= sizeof(text) - offset) {
                offset = sizeof(text) - 1;
                break;
            }
            offset += (size_t)written;
        }
    }
    snprintf(text + offset, sizeof(text) - offset, "hwdec=%s",
             p->decoder[0] ? p->decoder : "unavailable");
    return (*env)->NewStringUTF(env, text);
}
