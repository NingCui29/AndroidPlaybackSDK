#include "playback_input.h"
#include <errno.h>
#include <limits.h>
#include <poll.h>
#include <stdatomic.h>
#include <string.h>
#include <unistd.h>
#include <libavutil/time.h>

static atomic_int sequential_pollers;
enum { PREFIX_LIMIT = 512 * 1024, PROBE_LIMIT = 5 * 1024 * 1024 };

int playback_sequential_pollers(void) {
    return atomic_load(&sequential_pollers);
}

static int interrupted(void *opaque) {
    PlaybackInput *in = opaque;
    struct pollfd cancel = {in->cancel_fd, POLLIN, 0};
    return poll(&cancel, 1, 0) > 0 ||
        (in->deadline_us && av_gettime_relative() >= in->deadline_us) ||
        (in->operation_deadline_us && av_gettime_relative() >= in->operation_deadline_us);
}

static int read_bytes(void *opaque, uint8_t *buffer, int count) {
    PlaybackInput *in = opaque;
    if (in->length >= 0) {
        int64_t left = in->length - in->position;
        if (!left) return AVERROR_EOF;
        if (count > left) count = (int)left;
    }
    if (interrupted(in)) {
        if (in->deadline_us && av_gettime_relative() >= in->deadline_us) return AVERROR(ETIMEDOUT);
        return AVERROR_EXIT;
    }
    if (!in->seekable && in->position < in->prefix_size) {
        int copied = FFMIN(count, in->prefix_size - in->position);
        memcpy(buffer, in->prefix + in->position, copied);
        in->position += copied;
        return copied;
    }
    if (!in->seekable && in->position != in->source_position) return AVERROR(ESPIPE);
    if (in->probe_remaining == 0) { in->probe_limit = AVERROR(ENOBUFS); return in->probe_limit; }
    if (in->probe_remaining > 0 && count > in->probe_remaining)
        count = (int)in->probe_remaining;
    int64_t deadline = in->deadline_us ? in->deadline_us : av_gettime_relative() + 10000000;
    if (in->operation_deadline_us) deadline = FFMIN(deadline, in->operation_deadline_us);
    for (;;) {
        struct pollfd fds[] = {{in->fd, POLLIN, 0}, {in->cancel_fd, POLLIN, 0}};
        int64_t left = deadline - av_gettime_relative();
        if (left <= 0) return AVERROR(ETIMEDOUT);
        if (!in->seekable) atomic_fetch_add(&sequential_pollers, 1);
        int ready = poll(fds, 2, (int)((left + 999) / 1000));
        if (!in->seekable) atomic_fetch_sub(&sequential_pollers, 1);
        if (ready < 0 && errno == EINTR) continue;
        if (ready < 0) return AVERROR(errno);
        if (!ready) return AVERROR(ETIMEDOUT);
        if (fds[1].revents) return AVERROR_EXIT;
        ssize_t n = in->seekable ? pread(in->fd, buffer, count, in->start + in->position)
                                 : read(in->fd, buffer, count);
        if (n < 0 && (errno == EINTR || errno == EAGAIN)) continue;
        if (n < 0) return AVERROR(errno);
        if (!n) return AVERROR_EOF;
        if (!in->seekable) {
            if (!in->prefix_expired && in->source_position == in->prefix_size) {
                if (!in->prefix) {
                    in->prefix = av_malloc(PREFIX_LIMIT);
                    if (!in->prefix) return AVERROR(ENOMEM);
                }
                if (n <= PREFIX_LIMIT - in->prefix_size) {
                    // Cap the actual allocation as well as the logical prefix.
                    memcpy(in->prefix + in->prefix_size, buffer, n);
                    in->prefix_size += n;
                } else {
                    // Once a gap exists, rereading a prefix cannot reconnect to the live FD.
                    av_freep(&in->prefix);
                    in->prefix_expired = 1;
                    in->prefix_size = 0;
                }
            }
            in->source_position += n;
        }
        in->position += n;
        if (in->probe_remaining >= 0) in->probe_remaining -= n;
        return (int)n;
    }
}

static int64_t seek_bytes(void *opaque, int64_t delta, int whence) {
    PlaybackInput *in = opaque;
    whence &= ~AVSEEK_FORCE;
    if (whence == AVSEEK_SIZE) return in->length >= 0 ? in->length : AVERROR(ENOSYS);
    int64_t base;
    switch (whence) {
        case SEEK_SET: base = 0; break;
        case SEEK_CUR: base = in->position; break;
        case SEEK_END:
            if (in->length < 0) return AVERROR(ENOSYS);
            base = in->length; break;
        default: return AVERROR(EINVAL);
    }
    int64_t pos;
    if (__builtin_add_overflow(base, delta, &pos) || pos < 0 ||
        pos > INT64_MAX - in->start || (in->length >= 0 && pos > in->length))
        return AVERROR(EINVAL);
    if (!in->seekable && pos != in->source_position &&
        (pos > in->prefix_size || in->source_position != in->prefix_size)) return AVERROR(ESPIPE);
    return in->position = pos;
}

int playback_probe(PlaybackInput *in) {
    in->deadline_us = av_gettime_relative() + 5000000;
    in->probe_remaining = PROBE_LIMIT;
    uint8_t *buffer = av_malloc(32768);
    if (!buffer) return AVERROR(ENOMEM);
    in->io = avio_alloc_context(buffer, 32768, 0, in, read_bytes, NULL, seek_bytes);
    if (!in->io) { av_free(buffer); return AVERROR(ENOMEM); }
    in->io->seekable = in->seekable ? AVIO_SEEKABLE_NORMAL : 0;
    in->format = avformat_alloc_context();
    if (!in->format) return AVERROR(ENOMEM);
    in->format->pb = in->io;
    in->format->flags |= AVFMT_FLAG_CUSTOM_IO;
    in->format->probesize = in->probe_remaining;
    in->format->format_probesize = 512 * 1024;
    in->format->max_analyze_duration = 3000000;
    in->format->max_streams = 32;
    in->format->interrupt_callback = (AVIOInterruptCB){interrupted, in};
    int ret = avformat_open_input(&in->format, NULL, NULL, NULL);
    if (ret >= 0) ret = avformat_find_stream_info(in->format, NULL);
    in->probe_bytes = PROBE_LIMIT - in->probe_remaining;
    if (av_gettime_relative() >= in->deadline_us) in->probe_limit = AVERROR(ETIMEDOUT);
    if (ret >= 0 && in->probe_limit) {
        int usable = 0;
        for (unsigned i = 0; i < in->format->nb_streams; ++i) {
            const AVStream *s = in->format->streams[i];
            const AVCodecParameters *p = s->codecpar;
            if (p->codec_id == AV_CODEC_ID_NONE || s->time_base.num <= 0 || s->time_base.den <= 0) continue;
            if (p->codec_type == AVMEDIA_TYPE_AUDIO && p->sample_rate > 0 && p->ch_layout.nb_channels > 0) usable = 1;
            if (p->codec_type == AVMEDIA_TYPE_VIDEO && !(s->disposition & AV_DISPOSITION_ATTACHED_PIC) &&
                    p->width > 0 && p->height > 0) usable = 1;
        }
        if (!usable) ret = in->probe_limit;
        else if (in->io->error == AVERROR(ENOBUFS) || in->io->error == AVERROR(ETIMEDOUT)) {
            in->io->error = 0;
            in->io->eof_reached = 0;
        }
    }
    if (ret < 0 && in->probe_limit) ret = in->probe_limit;
    in->deadline_us = 0;
    in->probe_remaining = -1;
    return ret;
}

void playback_close(PlaybackInput *in) {
    avformat_close_input(&in->format);
    av_freep(&in->prefix);
    if (in->io) av_freep(&in->io->buffer);
    avio_context_free(&in->io);
}
