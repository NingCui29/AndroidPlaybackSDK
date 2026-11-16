#include "audio_track_probe.h"
#include "software_decoder.h"
#include <jni.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <libavutil/time.h>
#include <libavutil/common.h>

enum { PACKETS = 256, BYTES = 2 * 1024 * 1024 };
typedef struct AudioHistory {
    atomic_int refs;
    pthread_mutex_t mutex;
    pthread_cond_t changed;
    AVFormatContext *tracks;  // immutable descriptors copied after stream discovery
    AVPacket *packets[PACKETS];
    int64_t oldest, next, origin, times[PACKETS];
    size_t sizes[PACKETS], bytes;
} AudioHistory;

typedef struct AudioProbe {
    AudioHistory *history;
    PlaybackInput cursor;
    int index, rate, channels;
    int64_t target, actual;
} AudioProbe;

static void evict(AudioHistory *h) {
    int i = h->oldest++ % PACKETS;
    h->bytes -= h->sizes[i];
    av_packet_free(&h->packets[i]);
}

void audio_history_clear(PlaybackInput *in) {
    AudioHistory *h = in->audio_history;
    if (!h) return;
    pthread_mutex_lock(&h->mutex);
    while (h->oldest < h->next) evict(h);
    pthread_mutex_unlock(&h->mutex);
}

void audio_history_release(struct AudioHistory **slot) {
    AudioHistory *h = *slot;
    *slot = NULL;
    if (!h || atomic_fetch_sub(&h->refs, 1) != 1) return;
    while (h->oldest < h->next) evict(h);
    avformat_free_context(h->tracks);
    pthread_cond_destroy(&h->changed);
    pthread_mutex_destroy(&h->mutex);
    av_free(h);
}

int audio_history_init(PlaybackInput *in) {
    int audio = 0;
    for (unsigned i = 0; i < in->format->nb_streams; i++)
        audio += in->format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO;
    if (audio < 2) return 0;
    AudioHistory *h = av_mallocz(sizeof(*h));
    if (!h) return AVERROR(ENOMEM);
    atomic_init(&h->refs, 1);
    int err = pthread_mutex_init(&h->mutex, NULL);
    if (err) { av_free(h); return AVERROR(err); }
    err = pthread_cond_init(&h->changed, NULL);
    if (err) { pthread_mutex_destroy(&h->mutex); av_free(h); return AVERROR(err); }
    h->tracks = avformat_alloc_context();
    int ret = h->tracks ? 0 : AVERROR(ENOMEM);
    for (unsigned i = 0; ret >= 0 && i < in->format->nb_streams; i++) {
        AVStream *source = in->format->streams[i], *copy = avformat_new_stream(h->tracks, NULL);
        if (!copy) { ret = AVERROR(ENOMEM); break; }
        copy->time_base = source->time_base;
        if (source->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
            ret = avcodec_parameters_copy(copy->codecpar, source->codecpar);
    }
    h->origin = in->format->start_time == AV_NOPTS_VALUE ? 0 : in->format->start_time;
    if (ret < 0) audio_history_release(&h);
    else in->audio_history = h;
    return ret;
}

void audio_history_record(PlaybackInput *in, const AVPacket *p) {
    AudioHistory *h = in->audio_history;
    if (!h || in->seekable || p->size < 0 || p->size > 512 * 1024) return;
    size_t size = p->size;
    for (int i = 0; i < p->side_data_elems; i++) {
        if (p->side_data[i].size > 512 * 1024 - size) return;
        size += p->side_data[i].size;
    }
    int64_t pts = p->pts == AV_NOPTS_VALUE ? p->dts : p->pts;
    int64_t time = pts == AV_NOPTS_VALUE ? AV_NOPTS_VALUE :
            av_rescale_q(pts, h->tracks->streams[p->stream_index]->time_base, AV_TIME_BASE_Q);
    AVPacket *copy = av_packet_clone(p);
    if (!copy) return;
    pthread_mutex_lock(&h->mutex);
    while (h->oldest < h->next && (h->next - h->oldest >= PACKETS || size > BYTES - h->bytes ||
            (time != AV_NOPTS_VALUE && h->times[h->oldest % PACKETS] != AV_NOPTS_VALUE &&
            av_sat_sub64(time,h->times[h->oldest % PACKETS]) > 2000000))) evict(h);
    int i = h->next++ % PACKETS;
    h->packets[i] = copy; h->sizes[i] = size; h->times[i] = time; h->bytes += size;
    pthread_cond_broadcast(&h->changed);
    pthread_mutex_unlock(&h->mutex);
}

static int interrupted(AudioProbe *p) {
    struct pollfd fd = {p->cursor.cancel_fd, POLLIN, 0};
    if (poll(&fd, 1, 0) > 0) return AVERROR_EXIT;
    return av_gettime_relative() >= p->cursor.operation_deadline_us ? AVERROR(ETIMEDOUT) : 0;
}

static int history_next(AudioProbe *p, int64_t *serial, AVPacket *packet) {
    AudioHistory *h = p->history;
    pthread_mutex_lock(&h->mutex);
    int ret;
    while ((ret = interrupted(p)) == 0) {
        if (*serial < h->oldest) *serial = h->oldest;
        if (*serial < h->next) {
            AVPacket *candidate = h->packets[(*serial)++ % PACKETS];
            if (candidate->stream_index != p->index) continue;
            ret = av_packet_ref(packet, candidate);
            break;
        }
        struct timespec until;
        clock_gettime(CLOCK_REALTIME, &until);
        until.tv_nsec += 10000000;
        if (until.tv_nsec >= 1000000000) { until.tv_sec++; until.tv_nsec -= 1000000000; }
        pthread_cond_timedwait(&h->changed, &h->mutex, &until);
    }
    pthread_mutex_unlock(&h->mutex);
    return ret;
}

static int inspect_frame(void *opaque, const AVFrame *frame) {
    AudioProbe *p = opaque;
    if (p->rate) return 0;
    int rate = frame->sample_rate;
    if (rate <= 0 || frame->nb_samples <= 0 || frame->ch_layout.nb_channels < 1 ||
            (frame->ch_layout.nb_channels > 2 && frame->ch_layout.order == AV_CHANNEL_ORDER_UNSPEC))
        return AVERROR_INVALIDDATA;
    int64_t pts = frame->best_effort_timestamp;
    AVStream *stream = p->history->tracks->streams[p->index];
    pts = pts == AV_NOPTS_VALUE ? p->target :
            av_sat_sub64(av_rescale_q(pts,stream->time_base,AV_TIME_BASE_Q),p->history->origin);
    if (av_sat_add64(pts,av_rescale(frame->nb_samples,1000000,rate)) <= p->target) return 0;
    p->rate = rate;
    p->channels = frame->ch_layout.nb_channels == 1 ? 1 : 2;
    p->actual = FFMAX(pts, p->target);
    return 0;
}

#define PROBE(name) Java_com_cn_media_playback_AudioTrackProbe_##name
JNIEXPORT jlong JNICALL PROBE(create)(JNIEnv *env, jclass cls, jlong handle, jint index, jlong target) {
    PlaybackInput *source = (void *)(intptr_t)handle;
    AudioHistory *h = source->audio_history;
    if (!h || (unsigned)index >= h->tracks->nb_streams) return 0;
    AudioProbe *p = av_mallocz(sizeof(*p));
    if (!p) return 0;
    p->index = index; p->target = target * 1000;
    p->cursor.cancel_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    p->cursor.fd = source->seekable ? fcntl(source->fd, F_DUPFD_CLOEXEC, 0) : -1;
    if (p->cursor.cancel_fd < 0 || (source->seekable && p->cursor.fd < 0)) {
        if (p->cursor.cancel_fd >= 0) close(p->cursor.cancel_fd);
        if (p->cursor.fd >= 0) close(p->cursor.fd);
        av_free(p); return 0;
    }
    p->cursor.seekable = source->seekable;
    p->cursor.start = source->start; p->cursor.length = source->length;
    p->cursor.operation_deadline_us = av_gettime_relative() + 2000000;
    p->history = h; atomic_fetch_add(&h->refs, 1);
    return (jlong)(intptr_t)p;
}

JNIEXPORT jint JNICALL PROBE(inspect)(JNIEnv *env, jclass cls, jlong handle, jlongArray values) {
    AudioProbe *p = (void *)(intptr_t)handle;
    PlaybackInput *c = &p->cursor;
    int ret = 0;
    AVStream *stream = p->history->tracks->streams[p->index];
    if (c->seekable) {
        ret = playback_probe(c);
        if (ret >= 0 && (unsigned)p->index >= c->format->nb_streams) ret = AVERROR_STREAM_NOT_FOUND;
        if (ret >= 0) {
            stream = c->format->streams[p->index];
            int64_t ts = av_rescale_q(av_sat_add64(p->target,p->history->origin), AV_TIME_BASE_Q, stream->time_base);
            ret = avformat_seek_file(c->format, p->index, INT64_MIN, ts, ts, 0);
        }
    }
    SoftwareDecoder decoder = {0};
    AVPacket *packet = av_packet_alloc();
    if (!packet) ret = AVERROR(ENOMEM);
    if (ret >= 0) ret = software_decoder_open(&decoder, stream);
    int64_t serial = 0;
    while (ret >= 0 && !p->rate) {
        ret = interrupted(p);
        if (ret < 0) break;
        ret = c->seekable ? av_read_frame(c->format, packet) : history_next(p,&serial,packet);
        if (ret == AVERROR_EOF) {
            ret = software_decoder_submit(&decoder, NULL, inspect_frame, p);
            if (ret >= 0 && !p->rate) ret = AVERROR_EOF;
            break;
        }
        if (ret >= 0 && packet->stream_index == p->index)
            ret = software_decoder_submit(&decoder, packet, inspect_frame, p);
        av_packet_unref(packet);
    }
    if (ret >= 0) {
        jlong result[] = {p->rate,p->channels,p->actual};
        (*env)->SetLongArrayRegion(env, values, 0, 3, result);
    }
    software_decoder_close(&decoder);
    av_packet_free(&packet);
    return ret;
}

JNIEXPORT void JNICALL PROBE(signal)(JNIEnv *env, jclass cls, jlong handle) {
    AudioProbe *p = (void *)(intptr_t)handle;
    while (eventfd_write(p->cursor.cancel_fd, 1) < 0 && errno == EINTR) {}
}

JNIEXPORT void JNICALL PROBE(destroy)(JNIEnv *env, jclass cls, jlong handle) {
    AudioProbe *p = (void *)(intptr_t)handle;
    playback_close(&p->cursor);
    if (p->cursor.fd >= 0) close(p->cursor.fd);
    close(p->cursor.cancel_fd);
    audio_history_release(&p->history);
    av_free(p);
}
