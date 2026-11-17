#pragma once
#include "decode_tracks.h"
#include "rgba_renderer.h"
#include <android/native_window_jni.h>
#include <libswresample/swresample.h>
#include <libswscale/swscale.h>
typedef struct AudioSink {
    JNIEnv *env;
    jobject output;
    jmethodID configure, write;
    SwrContext *swr;
    AVChannelLayout layout;
    int rate, format, channels;
} AudioSink;
typedef struct VideoSink {
    ANativeWindow *window;
    RgbaRenderer renderer;
    AVRational sar;
    int rotation, posted, cancel_fd, geometry_set;
    int window_w, window_h;
    int buffer_w, buffer_h;
    int frame_w, frame_h;
} VideoSink;
void video_stream_geometry(VideoSink *sink, const AVStream *stream);
int audio_deliver(void *opaque, const AVFrame *frame);
int audio_deliver_from(AudioSink *sink, const AVFrame *frame, int skip);
int audio_flush(AudioSink *s);
int video_post(void *opaque, const AVFrame *frame);
