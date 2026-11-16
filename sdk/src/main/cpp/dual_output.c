#include <pthread.h>
#include "output_sinks.h"

// The decode workers are native pthreads. Attach for the worker lifetime
// and use a global reference for the Java output object.
typedef struct AndroidAudio {
    AudioSink sink;
    JavaVM *vm;
    pthread_key_t attachment;
} AndroidAudio;

static void detach(void *vm) {
    JavaVM *machine = vm;
    (*machine)->DetachCurrentThread(machine);
}

static int on_audio(void *opaque, const AVFrame *frame) {
    AndroidAudio *audio = opaque;
    if (!audio->sink.env) {
        if ((*audio->vm)->AttachCurrentThread(audio->vm, &audio->sink.env, NULL) != JNI_OK)
            return AVERROR_EXTERNAL;
        if (pthread_setspecific(audio->attachment, audio->vm)) {
            detach(audio->vm);
            return AVERROR_EXTERNAL;
        }
    }
    int ret = audio_deliver(&audio->sink, frame);
    if ((*audio->sink.env)->ExceptionCheck(audio->sink.env)) {
        (*audio->sink.env)->ExceptionClear(audio->sink.env);
        return AVERROR_EXTERNAL;
    }
    return ret;
}

JNIEXPORT jint JNICALL Java_com_cn_media_playback_NativeInput_playBoth(
        JNIEnv *env, jclass cls, jlong handle, jobject output, jobject surface) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    int a = av_find_best_stream(in->format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    int v = av_find_best_stream(in->format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (a < 0 || v < 0) return AVERROR_STREAM_NOT_FOUND;
    AndroidAudio audio = {0};
    if ((*env)->GetJavaVM(env, &audio.vm) != JNI_OK) return AVERROR_EXTERNAL;
    jclass output_class = (*env)->GetObjectClass(env, output);
    audio.sink.configure = (*env)->GetMethodID(env, output_class, "configure", "(II)I");
    if (!audio.sink.configure) return AVERROR_EXTERNAL;
    audio.sink.write = (*env)->GetMethodID(env, output_class, "write", "(Ljava/nio/ByteBuffer;)I");
    if (!audio.sink.write) return AVERROR_EXTERNAL;
    int err = pthread_key_create(&audio.attachment, detach);
    if (err) return AVERROR(err);
    audio.sink.output = (*env)->NewGlobalRef(env, output);
    VideoSink video = {.cancel_fd=in->cancel_fd};
    video_stream_geometry(&video, in->format->streams[v]);
    if (audio.sink.output) video.window = ANativeWindow_fromSurface(env, surface);
    int ret = AVERROR_EXTERNAL;
    if (video.window) {
        DecodeTarget targets[] = {{a, on_audio, &audio}, {v, video_post, &video}};
        ret = playback_decode_tracks(in, targets, 2);
        audio.sink.env = env;
        if (ret >= 0) ret = audio_flush(&audio.sink);
        ANativeWindow_release(video.window);
    }
    rgba_renderer_close(&video.renderer);
    swr_free(&audio.sink.swr);
    av_channel_layout_uninit(&audio.sink.layout);
    if (audio.sink.output) (*env)->DeleteGlobalRef(env, audio.sink.output);
    pthread_key_delete(audio.attachment);
    return ret < 0 ? ret : video.posted;
}
