#include "playback_input.h"
#include <jni.h>
#include "audio_track_probe.h"
#include "decode_tracks.h"
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <sys/eventfd.h>
#include <libavutil/time.h>

#define JNI_INPUT(name) Java_com_cn_media_playback_NativeInput_##name
static atomic_int live_input_fds;

JNIEXPORT jint JNICALL JNI_INPUT(liveInputFds)(JNIEnv *env, jclass cls) {
    return atomic_load(&live_input_fds);
}

JNIEXPORT jint JNICALL JNI_INPUT(waitingSequential)(JNIEnv *env, jclass cls) {
    return playback_sequential_pollers();
}

JNIEXPORT jlong JNICALL JNI_INPUT(create)(JNIEnv *env, jclass cls, jint fd,
        jlong start, jlong length, jboolean seekable) {
    PlaybackInput *in = calloc(1, sizeof(*in));
    if (!in) return 0;
    in->fd = fcntl(fd, F_DUPFD_CLOEXEC, 0);
    in->cancel_fd = eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK);
    if (in->fd < 0 || in->cancel_fd < 0) {
        if (in->fd >= 0) close(in->fd);
        if (in->cancel_fd >= 0) close(in->cancel_fd);
        free(in);
        return 0;
    }
    pthread_mutex_init(&in->group_lock, NULL);
    in->start = start;
    in->length = length;
    in->seekable = seekable;
    atomic_fetch_add(&live_input_fds, 2);
    return (jlong)(intptr_t)in;
}

JNIEXPORT jint JNICALL JNI_INPUT(probe)(JNIEnv *env, jclass cls, jlong handle) {
    PlaybackInput *in = (void *)(intptr_t)handle;
    int ret = playback_probe(in);
    return ret < 0 ? ret : audio_history_init(in);
}

JNIEXPORT void JNICALL JNI_INPUT(rearm)(JNIEnv *env, jclass cls, jlong handle) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    eventfd_t value;
    while (eventfd_read(in->cancel_fd, &value) < 0 && errno == EINTR) {}
    if (in->io) { in->io->eof_reached = 0; in->io->error = 0; }
}

static int seek_interrupted(PlaybackInput *in) {
    if (av_gettime_relative() >= in->deadline_us) return AVERROR(ETIMEDOUT);
    return in->format->interrupt_callback.callback(in) ? AVERROR_EXIT : 0;
}

static int seek_video(PlaybackInput *in, int index, int64_t timestamp, int mpegts, int at_start) {
    // A timestamp seek in TS can skip the first GOP even at the format origin.
    // Rewind the bounded AVIO to its first packet for both the probe and the replay.
    if (mpegts && at_start)
        return av_seek_frame(in->format, -1, 0, AVSEEK_FLAG_BYTE);
    if (mpegts)
        return avformat_seek_file(in->format, index, INT64_MIN, timestamp, timestamp, 0);
    return av_seek_frame(in->format, index, timestamp, AVSEEK_FLAG_BACKWARD);
}

static int64_t previous_asf_index_time(AVStream *stream, int64_t timestamp) {
    int index = av_index_search_timestamp(stream, timestamp, AVSEEK_FLAG_BACKWARD);
    if (index <= 0) return AV_NOPTS_VALUE;
    const AVIndexEntry *entry = avformat_index_get_entry(stream, index - 1);
    if (!entry) return AV_NOPTS_VALUE;
    // FFmpeg reserves a seek timestamp of 0 for the physical start of ASF.
    // A positive request is needed to visit an index entry labeled zero.
    return entry->timestamp == 0 ? 1 : entry->timestamp;
}

static int seek_verified_video(PlaybackInput *in, int index, int64_t origin,
                               int64_t offset, int mpegts, int exact) {
    // ASF index timestamps and MPEG-TS seek timestamps can both select a position whose
    // first decodable video packet is later than the requested display time.
    AVStream *stream = in->format->streams[index];
    const int64_t target_us = origin + offset;
    const int64_t target = av_rescale_q_rnd(target_us, AV_TIME_BASE_Q,
                                           stream->time_base, AV_ROUND_DOWN);
    AVPacket *packet = av_packet_alloc();
    if (!packet) return AVERROR(ENOMEM);
    in->deadline_us = av_gettime_relative() + 3000000;
    int ret = AVERROR_INVALIDDATA;
    int asf_index_steps = 0;
    for (int64_t backoff = 0;;) {
        int at_start = backoff == offset;
        int64_t probe_us = target_us - backoff;
        int64_t timestamp = !mpegts && at_start ? 0 :
                av_rescale_q_rnd(probe_us, AV_TIME_BASE_Q, stream->time_base, AV_ROUND_DOWN);
        ret = seek_video(in, index, timestamp, mpegts, at_start);
        int64_t key_timestamp = AV_NOPTS_VALUE;
        while (ret >= 0) {
            ret = seek_interrupted(in);
            if (ret < 0) break;
            ret = av_read_frame(in->format, packet);
            if (ret < 0) break;
            int key = packet->stream_index == index && (packet->flags & AV_PKT_FLAG_KEY);
            if (key) {
                key_timestamp = packet->pts;
                // ASF can supply only DTS for reordered video such as VC-1.
                // Use it to choose the decode entry point. Exact Seek checks
                // the first decoded frame's presentation time separately.
                if (key_timestamp == AV_NOPTS_VALUE &&
                        (!mpegts || !stream->codecpar->video_delay))
                    key_timestamp = packet->dts;
            }
            av_packet_unref(packet);
            if (key) break;
        }
        int interrupted = seek_interrupted(in);
        if (interrupted < 0) { ret = interrupted; break; }
        // A rewind may start with audio before the first video frame. Exact
        // seeks validate that frame at presentation time, including the
        // one-frame tolerance. Positive fast seeks require an earlier key.
        if (key_timestamp != AV_NOPTS_VALUE &&
                (key_timestamp <= target || (at_start && (offset == 0 || exact)))) {
            // Rewind to the verified demux position so the decoder can consume
            // the original packets, including interleaved audio before the key.
            ret = seek_video(in, index, timestamp, mpegts, at_start);
            interrupted = seek_interrupted(in);
            if (interrupted < 0) ret = interrupted;
            break;
        }
        if (ret == AVERROR_EXIT || ret == AVERROR(ETIMEDOUT) || ret == AVERROR(ENOMEM))
            break;
        if (at_start) {
            if (ret >= 0 || ret == AVERROR_EOF) ret = AVERROR(ESPIPE);
            break;
        }
        // Prefer the previous indexed position before exponential backoff.
        // Otherwise one large step can jump over a usable keyframe and force
        // decoding an extra GOP. Cap linear index walking on damaged indexes.
        if (!mpegts && asf_index_steps < 8) {
            int64_t previous = previous_asf_index_time(stream, timestamp);
            if (previous != AV_NOPTS_VALUE && previous < timestamp) {
                int64_t previous_us = av_rescale_q_rnd(previous, stream->time_base,
                                                       AV_TIME_BASE_Q, AV_ROUND_UP);
                if (previous_us > origin && previous_us < probe_us) {
                    backoff = target_us - previous_us;
                    asf_index_steps++;
                    continue;
                }
            }
        }
        backoff = backoff == 0 ? FFMIN(offset, 1000000) :
                  backoff + FFMIN(backoff, offset - backoff);
    }
    av_packet_free(&packet);
    in->deadline_us = 0;
    return ret;
}

JNIEXPORT jint JNICALL JNI_INPUT(seek)(JNIEnv *env, jclass cls, jlong handle, jint index, jlong ms,
                                       jboolean exact) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->seekable) return AVERROR(ESPIPE);
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams ||
            ms < 0 || ms > INT64_MAX / 1000)
        return AVERROR(EINVAL);
    int64_t origin = in->format->start_time == AV_NOPTS_VALUE ? 0 : in->format->start_time;
    int64_t offset = ms * 1000;
    int64_t target_us;
    if (__builtin_add_overflow(origin, offset, &target_us)) return AVERROR(EINVAL);
    const AVStream *stream = in->format->streams[index];
    int video = stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO;
    const char *format = in->format->iformat->name;
    int mpegts = !strcmp(format, "mpegts");
    in->wait_for_video_key = 0;
    audio_history_clear(in);
    int ret;
    // Fast Seek also needs a keyframe before the requested position.
    if (video && (mpegts || !strcmp(format, "asf"))) {
        ret = seek_verified_video(in, index, origin, offset, mpegts, exact);
    } else {
        int64_t target = av_rescale_q(target_us, AV_TIME_BASE_Q, stream->time_base);
        ret = av_seek_frame(in->format, index, target, AVSEEK_FLAG_BACKWARD);
    }
    if (ret >= 0) in->wait_for_video_key = video;
    return ret;
}

JNIEXPORT jlong JNICALL JNI_INPUT(durationMs)(JNIEnv *env, jclass cls, jlong handle) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || in->format->duration <= 0) return -1;
    return in->format->duration / (AV_TIME_BASE / 1000);
}

JNIEXPORT jstring JNICALL JNI_INPUT(trackCodec)(JNIEnv *env, jclass cls, jlong handle, jint index) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams) return NULL;
    const AVCodecDescriptor *desc = avcodec_descriptor_get(in->format->streams[index]->codecpar->codec_id);
    return desc ? (*env)->NewStringUTF(env, desc->name) : NULL;
}

JNIEXPORT jint JNICALL JNI_INPUT(trackChannels)(JNIEnv *env, jclass cls, jlong handle, jint index) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams) return -1;
    return in->format->streams[index]->codecpar->ch_layout.nb_channels;
}

JNIEXPORT jint JNICALL JNI_INPUT(trackSampleRate)(JNIEnv *env, jclass cls, jlong handle, jint index) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams) return -1;
    return in->format->streams[index]->codecpar->sample_rate;
}

JNIEXPORT jint JNICALL JNI_INPUT(trackWidth)(JNIEnv *env, jclass cls, jlong handle, jint index) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams) return -1;
    return in->format->streams[index]->codecpar->width;
}

JNIEXPORT jint JNICALL JNI_INPUT(trackHeight)(JNIEnv *env, jclass cls, jlong handle, jint index) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams) return -1;
    return in->format->streams[index]->codecpar->height;
}

JNIEXPORT jint JNICALL JNI_INPUT(trackRotation)(JNIEnv *env, jclass cls, jlong handle, jint index) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams) return 0;
    AVDictionaryEntry *rotate = av_dict_get(in->format->streams[index]->metadata, "rotate", NULL, 0);
    return rotate ? atoi(rotate->value) : 0;
}

JNIEXPORT jint JNICALL JNI_INPUT(trackCount)(JNIEnv *env, jclass cls, jlong handle) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    return in->format ? in->format->nb_streams : 0;
}

JNIEXPORT jint JNICALL JNI_INPUT(trackKind)(JNIEnv *env, jclass cls, jlong handle, jint index) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || index < 0 || (unsigned)index >= in->format->nb_streams) return -1;
    enum AVMediaType type = in->format->streams[index]->codecpar->codec_type;
    return type == AVMEDIA_TYPE_AUDIO ? 1 : type == AVMEDIA_TYPE_VIDEO ? 2 : 0;
}

JNIEXPORT jint JNICALL JNI_INPUT(category)(JNIEnv *env, jclass cls, jint status, jboolean probing) {
    if (status >= 0) return 0;
    if (status == PLAYBACK_HW_INIT) return 4001;
    if (status == PLAYBACK_HW_RUNTIME) return 4002;
    if (status == AVERROR(EACCES) || status == AVERROR(EPERM)) return 2001;
    if (status == AVERROR(ETIMEDOUT)) return 2003;
    if (status == AVERROR(ENOMEM) || status == AVERROR(ENOBUFS)) return 5002;
    if (status == AVERROR(ESPIPE)) return 1003;
    if (status == AVERROR_DEMUXER_NOT_FOUND) return 3001;
    if (status == AVERROR_INVALIDDATA) return probing ? 3001 : 3003;
    if (status == AVERROR_DECODER_NOT_FOUND) return 3002;
    if (status == AVERROR_STREAM_NOT_FOUND) return 3004;
    if (status == AVERROR_EXTERNAL) return 5001;
    return probing ? 2002 : 4002;
}

JNIEXPORT jboolean JNICALL JNI_INPUT(recoverHardware)(JNIEnv *env, jclass cls, jint status) {
    return status == PLAYBACK_HW_RUNTIME;
}

JNIEXPORT void JNICALL JNI_INPUT(cancel)(JNIEnv *env, jclass cls, jlong handle) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in) return;
    eventfd_t signal = 1;
    while (eventfd_write(in->cancel_fd, signal) < 0 && errno == EINTR) {}
    playback_abort_decode(in);
}

JNIEXPORT void JNICALL JNI_INPUT(destroy)(JNIEnv *env, jclass cls, jlong handle) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in) return;
    playback_abort_decode(in);
    audio_history_release(&in->audio_history);
    playback_close(in);
    close(in->fd);
    close(in->cancel_fd);
    pthread_mutex_destroy(&in->group_lock);
    atomic_fetch_sub(&live_input_fds, 2);
    free(in);
}
