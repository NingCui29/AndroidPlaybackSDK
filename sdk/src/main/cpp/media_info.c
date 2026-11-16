#include "playback_input.h"
#include <libavcodec/avcodec.h>
#include <libavutil/display.h>
#include <jni.h>
#include <math.h>

// Language tags are ASCII. Ignore malformed metadata rather than passing arbitrary
// file bytes to NewStringUTF, which expects JNI modified UTF-8.
static const char *language_tag(const AVStream *stream) {
    AVDictionaryEntry *language = av_dict_get(stream->metadata, "language", NULL, 0);
    if (!language || strlen(language->value) > 63) return "";
    for (const char *p = language->value; *p; ++p)
        if (!((*p >= 'A' && *p <= 'Z') || (*p >= 'a' && *p <= 'z') ||
              (*p >= '0' && *p <= '9') || *p == '-')) return "";
    return language->value;
}

JNIEXPORT jobject JNICALL Java_com_cn_media_playback_NativeInput_describe(
        JNIEnv *env, jclass cls, jlong handle, jlong media_id) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    AVFormatContext *format = in->format;
    jclass track = (*env)->FindClass(env, "com/cn/media/playback/TrackInfo");
    if (!track) return NULL;
    jmethodID make_track = (*env)->GetMethodID(env, track, "<init>",
            "(JIILjava/lang/String;Ljava/lang/String;ZZZIIIII[BIIIII)V");
    if (!make_track) return NULL;
    jobjectArray tracks = (*env)->NewObjectArray(env, format->nb_streams, track, NULL);
    if (!tracks) return NULL;
    for (unsigned i = 0; i < format->nb_streams; ++i) {
        if ((*env)->PushLocalFrame(env, 6) < 0) return NULL;
        const AVStream *stream = format->streams[i];
        const AVCodecParameters *p = stream->codecpar;
        int kind;
        switch (p->codec_type) {
            case AVMEDIA_TYPE_AUDIO: kind = 0; break;
            case AVMEDIA_TYPE_VIDEO: kind = 1; break;
            case AVMEDIA_TYPE_SUBTITLE: kind = 2; break;
            default: kind = 3;
        }
        int rotation = 0;
        const AVPacketSideData *side = av_packet_side_data_get(p->coded_side_data,
                p->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
        if (side && side->size >= 9 * sizeof(int32_t)) {
            double degrees = -av_display_rotation_get((int32_t *)side->data);
            if (isfinite(degrees)) rotation = ((int)lround(degrees) % 360 + 360) % 360;
        }
        jstring codec = (*env)->NewStringUTF(env, avcodec_get_name(p->codec_id));
        if (!codec) return NULL;
        jstring lang = (*env)->NewStringUTF(env, language_tag(stream));
        if (!lang) return NULL;
        const AVDictionaryEntry *title = av_dict_get(stream->metadata, "title", NULL, 0);
        jsize length = title ? (jsize)strnlen(title->value, 512) : 0;
        jbyteArray title_bytes = (*env)->NewByteArray(env, length);
        if (!title_bytes) return NULL;
        if (length) (*env)->SetByteArrayRegion(env, title_bytes, 0, length, (const jbyte *)title->value);
        AVRational aspect = av_guess_sample_aspect_ratio(format, (AVStream *)stream, NULL);
        jobject value = (*env)->NewObject(env, track, make_track, media_id, (jint)i, kind,
                codec, lang, !!(stream->disposition & AV_DISPOSITION_DEFAULT),
                !!(stream->disposition & AV_DISPOSITION_ATTACHED_PIC), avcodec_find_decoder(p->codec_id) != NULL,
                p->width, p->height,
                p->sample_rate, p->ch_layout.nb_channels, rotation, title_bytes,
                stream->time_base.num, stream->time_base.den, aspect.num, aspect.den, p->extradata_size);
        if (!value) return NULL;
        (*env)->SetObjectArrayElement(env, tracks, i, value);
        (*env)->PopLocalFrame(env, NULL);
        if ((*env)->ExceptionCheck(env)) return NULL;
    }
    jclass media = (*env)->FindClass(env, "com/cn/media/playback/MediaInfo");
    if (!media) return NULL;
    jmethodID construct = (*env)->GetMethodID(env, media, "<init>",
            "(JJZ[Lcom/cn/media/playback/TrackInfo;JI)V");
    if (!construct) return NULL;
    jlong duration = format->duration == AV_NOPTS_VALUE ? -1 : format->duration / 1000;
    int seekable = in->seekable && (duration >= 0 ||
            (format->nb_streams && avformat_index_get_entries_count(format->streams[0]) > 0));
    int limit = in->probe_limit == AVERROR(ENOBUFS) ? 1 : in->probe_limit == AVERROR(ETIMEDOUT) ? 2 : 0;
    return (*env)->NewObject(env, media, construct, media_id, duration, seekable, tracks, (jlong)in->probe_bytes, limit);
}
