#include "playback_input.h"
#include "software_decoder.h"
#include <libswresample/swresample.h>
#include <jni.h>

#include "output_sinks.h"

static int convert(AudioSink *s, const AVFrame *frame, int skip) {
    int samples = frame ? frame->nb_samples - skip : 0;
    int capacity = swr_get_out_samples(s->swr, samples);
    if (capacity <= 0) return capacity;
    uint8_t *data = NULL;
    int ret = av_samples_alloc(&data, NULL, s->channels, capacity, AV_SAMPLE_FMT_S16, 1);
    if (ret < 0) return ret;
    const uint8_t **source = frame ? (const uint8_t **)frame->extended_data : NULL;
    const uint8_t **trimmed = NULL;
    if (skip) {
        int planar = av_sample_fmt_is_planar(frame->format);
        int planes = planar ? frame->ch_layout.nb_channels : 1;
        size_t stride = (size_t)av_get_bytes_per_sample(frame->format) * (planar ? 1 : frame->ch_layout.nb_channels);
        trimmed = av_calloc(planes, sizeof(*trimmed));
        if (!trimmed) { av_free(data); return AVERROR(ENOMEM); }
        for (int p = 0; p < planes; ++p) trimmed[p] = source[p] + skip * stride;
        source = trimmed;
    }
    ret = swr_convert(s->swr, &data, capacity, source, samples);
    av_free(trimmed);
    if (ret > 0) {
        JNIEnv *env = s->env;
        jobject buffer = (*env)->NewDirectByteBuffer(env, data, (jlong)ret * s->channels * 2);
        if (!buffer) ret = AVERROR(ENOMEM);
        else {
            jint status = (*env)->CallIntMethod(env, s->output, s->write, buffer);
            (*env)->DeleteLocalRef(env, buffer);
            if ((*env)->ExceptionCheck(env) || status != 0) ret = AVERROR_EXTERNAL;
        }
    }
    av_free(data);
    return ret;
}

int audio_deliver_from(AudioSink *s, const AVFrame *frame, int skip) {
    if (!s->swr) {
        if (frame->ch_layout.nb_channels < 1 || (frame->ch_layout.nb_channels > 2 &&
                frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC)) return AVERROR_INVALIDDATA;
        s->channels = frame->ch_layout.nb_channels == 1 ? 1 : 2;
        s->rate = frame->sample_rate;
        s->format = frame->format;
        int output_rate = (*s->env)->CallIntMethod(s->env, s->output, s->configure, s->rate, s->channels);
        if ((*s->env)->ExceptionCheck(s->env) || output_rate <= 0) return AVERROR_EXTERNAL;
        AVChannelLayout layout;
        av_channel_layout_default(&layout, s->channels);
        int ret = av_channel_layout_copy(&s->layout, &frame->ch_layout);
        if (ret >= 0) ret = swr_alloc_set_opts2(&s->swr, &layout, AV_SAMPLE_FMT_S16, output_rate,
                                                &s->layout, frame->format, s->rate, 0, NULL);
        av_channel_layout_uninit(&layout);
        if (ret < 0) return ret;
        ret = swr_init(s->swr);
        if (ret < 0) return ret;
    }
    if (frame->sample_rate != s->rate || frame->format != s->format ||
        av_channel_layout_compare(&frame->ch_layout, &s->layout)) return AVERROR_INPUT_CHANGED;
    return convert(s, frame, skip);
}

int audio_deliver(void *opaque, const AVFrame *frame) {
    return audio_deliver_from(opaque, frame, 0);
}

int audio_flush(AudioSink *s) {
    int ret = 0;
    if (s->swr) do { ret = convert(s, NULL, 0); } while (ret > 0);
    return ret;
}

JNIEXPORT jint JNICALL Java_com_cn_media_playback_NativeInput_playAudio(
        JNIEnv *env, jclass type, jlong handle, jobject output) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    int stream = av_find_best_stream(in->format, AVMEDIA_TYPE_AUDIO, -1, -1, NULL, 0);
    if (stream < 0) return stream;
    jclass cls = (*env)->GetObjectClass(env, output);
    AudioSink sink = {.env=env, .output=output};
    sink.configure = (*env)->GetMethodID(env, cls, "configure", "(II)I");
    if (!sink.configure) return AVERROR_EXTERNAL;
    sink.write = (*env)->GetMethodID(env, cls, "write", "(Ljava/nio/ByteBuffer;)I");
    if (!sink.write) return AVERROR_EXTERNAL;
    SoftwareDecoder decoder = {0};
    AVPacket *pkt = av_packet_alloc();
    int ret = pkt ? software_decoder_open(&decoder, in->format->streams[stream]) : AVERROR(ENOMEM);
    while (ret >= 0) {
        ret = av_read_frame(in->format, pkt);
        if (ret == AVERROR_EOF) {
            ret = software_decoder_submit(&decoder, NULL, audio_deliver, &sink);
            if (ret >= 0) ret = audio_flush(&sink);
            break;
        }
        if (ret < 0) break;
        if (pkt->stream_index == stream) ret = software_decoder_submit(&decoder, pkt, audio_deliver, &sink);
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);
    software_decoder_close(&decoder);
    swr_free(&sink.swr);
    av_channel_layout_uninit(&sink.layout);
    return ret;
}

