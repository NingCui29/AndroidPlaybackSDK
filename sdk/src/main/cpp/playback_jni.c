#include "playback_input.h"
#include <jni.h>
#include "audio_track_probe.h"
#include <stdint.h>
#include <stdatomic.h>
#include <stdlib.h>
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

JNIEXPORT jint JNICALL JNI_INPUT(seek)(JNIEnv *env, jclass cls, jlong handle, jint index, jlong ms) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->seekable) return AVERROR(ESPIPE);
    if ((unsigned)index >= in->format->nb_streams || ms < 0 || ms > INT64_MAX / 1000) return AVERROR(EINVAL);
    int64_t base = in->format->start_time == AV_NOPTS_VALUE ? 0 : in->format->start_time;
    audio_history_clear(in);
    int64_t us;
    if (__builtin_add_overflow(base, (int64_t)ms * 1000, &us)) return AVERROR(EINVAL);
    int64_t target = av_rescale_q_rnd(us, AV_TIME_BASE_Q, in->format->streams[index]->time_base, AV_ROUND_DOWN);
    const AVStream *stream = in->format->streams[index];
    in->wait_for_video_key = 0;
    if (stream->codecpar->codec_type != AVMEDIA_TYPE_VIDEO ||
        strcmp(in->format->iformat->name, "mpegts") != 0)
        return avformat_seek_file(in->format, index, INT64_MIN, target, target, 0);

    // MPEG-TS seeks by PCR. A successful byte landing may begin in the
    // middle of a GOP, even behind the final random-access picture.
    AVPacket *packet = av_packet_alloc();
    if (!packet) return AVERROR(ENOMEM);
    int ret = AVERROR_INVALIDDATA;
    int64_t back_us = 0;
    in->deadline_us = av_gettime_relative() + 3000000;
    for (;;) {
        int64_t probe_us = FFMAX(base, us - back_us);
        int64_t probe_ts = av_rescale_q(probe_us, AV_TIME_BASE_Q, stream->time_base);
        ret = avformat_seek_file(in->format, index, INT64_MIN, probe_ts, probe_ts, 0);
        if (ret < 0) break;
        int found = 0;
        while ((ret = av_read_frame(in->format, packet)) >= 0) {
            int key = packet->stream_index == index && (packet->flags & AV_PKT_FLAG_KEY);
            if (key) found = probe_us == base || (packet->pts != AV_NOPTS_VALUE && packet->pts <= target);
            av_packet_unref(packet);
            if (key) break;
        }
        if (found) {
            ret = avformat_seek_file(in->format, index, INT64_MIN, probe_ts, probe_ts, 0);
            if (ret >= 0) in->wait_for_video_key = 1;
            break;
        }
        if (ret < 0 && ret != AVERROR_EOF) break;
        if (in->format->interrupt_callback.callback(in)) { ret = AVERROR_EXIT; break; }
        if (probe_us == base) { ret = AVERROR_INVALIDDATA; break; }
        back_us = back_us == 0 ? 1000000 : FFMIN(us - base, back_us <= INT64_MAX / 2 ? back_us * 2 : INT64_MAX);
    }
    in->deadline_us = 0;
    av_packet_free(&packet);
    return ret;
}

JNIEXPORT jint JNICALL JNI_INPUT(category)(JNIEnv *env, jclass cls, jint status, jboolean probing) {
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
    eventfd_t signal = 1;
    while (eventfd_write(in->cancel_fd, signal) < 0 && errno == EINTR) {}
}

JNIEXPORT void JNICALL JNI_INPUT(destroy)(JNIEnv *env, jclass cls, jlong handle) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    audio_history_release(&in->audio_history);
    playback_close(in);
    close(in->fd);
    close(in->cancel_fd);
    atomic_fetch_sub(&live_input_fds, 2);
    free(in);
}
