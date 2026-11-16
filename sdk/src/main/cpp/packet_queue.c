#include "packet_queue.h"
#include <libavutil/mathematics.h>
#include <libavutil/error.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

enum { HIGH_US = 2000000 };

int packet_queue_init(PacketQueue *q, int video) {
    memset(q, 0, sizeof(*q));
    q->capacity = video ? 128 : 256;
    q->byte_limit = (video ? 8 : 2) * 1024 * 1024;
    q->single_limit = video ? 4 * 1024 * 1024 : 512 * 1024;
    q->slots = calloc(q->capacity, sizeof(*q->slots));
    if (!q->slots) return AVERROR(ENOMEM);
    int err = pthread_mutex_init(&q->lock, NULL);
    if (!err) {
        pthread_condattr_t attr;
        err = pthread_condattr_init(&attr);
        if (!err) {
            err = pthread_condattr_setclock(&attr, CLOCK_MONOTONIC);
            if (!err) err = pthread_cond_init(&q->changed, &attr);
            pthread_condattr_destroy(&attr);
        }
        if (err) pthread_mutex_destroy(&q->lock);
    }
    if (err) free(q->slots);
    return -err;
}

int packet_queue_push(PacketQueue *q, AVPacket *packet, AVRational time_base) {
    size_t bytes = packet->size;
    if (bytes > q->single_limit) return AVERROR(ENOBUFS);
    for (int i = 0; i < packet->side_data_elems; ++i) {
        size_t extra = packet->side_data[i].size;
        if (extra > q->single_limit - bytes) return AVERROR(ENOBUFS);
        bytes += extra;
    }
    int64_t us = packet->duration > 0 && time_base.num > 0 && time_base.den > 0
            ? av_rescale_q(packet->duration, time_base, (AVRational){1, 1000000}) : 0;
    if (us < 0) us = INT64_MAX;
    pthread_mutex_lock(&q->lock);
    while (!q->terminal) {
        if (q->throttled && q->count <= q->capacity / 2 &&
            q->bytes <= q->byte_limit / 2 && q->duration_us <= HIGH_US / 2)
            q->throttled = 0;
        if (!q->throttled && q->count < q->capacity && bytes <= q->byte_limit - q->bytes &&
            (!q->count || us <= HIGH_US - q->duration_us)) break;
        q->throttled = 1;
        pthread_cond_wait(&q->changed, &q->lock);
    }
    int ret = q->terminal;
    if (!ret) {
        QueuedPacket *slot = &q->slots[(q->head + q->count) % q->capacity];
        if (!slot->packet) slot->packet = av_packet_alloc();
        if (!slot->packet) ret = AVERROR(ENOMEM);
        else {
            av_packet_move_ref(slot->packet, packet);
            slot->bytes = bytes;
            slot->duration_us = us;
            q->count++;
            q->bytes += bytes;
            q->duration_us += us;
            q->throttled = q->count == q->capacity || q->bytes == q->byte_limit ||
                           q->duration_us >= HIGH_US;
            pthread_cond_broadcast(&q->changed);
        }
    }
    pthread_mutex_unlock(&q->lock);
    return ret;
}

static int pop(PacketQueue *q, AVPacket *empty, int64_t wait_us) {
    struct timespec until;
    if (wait_us > 0) {
        clock_gettime(CLOCK_MONOTONIC, &until);
        until.tv_sec += wait_us / 1000000;
        until.tv_nsec += (long)(wait_us % 1000000) * 1000;
        if (until.tv_nsec >= 1000000000L) { until.tv_sec++; until.tv_nsec -= 1000000000L; }
    }
    pthread_mutex_lock(&q->lock);
    while (wait_us && !q->count && !q->terminal) {
        int err = wait_us < 0 ? pthread_cond_wait(&q->changed, &q->lock)
                              : pthread_cond_timedwait(&q->changed, &q->lock, &until);
        if (err == ETIMEDOUT) break;
        if (err) { pthread_mutex_unlock(&q->lock); return AVERROR(err); }
    }
    int ret = q->terminal;
    if (q->count && (!ret || ret == AVERROR_EOF)) {
        QueuedPacket *slot = &q->slots[q->head];
        av_packet_move_ref(empty, slot->packet);
        q->head = (q->head + 1) % q->capacity;
        q->count--;
        q->bytes -= slot->bytes;
        q->duration_us -= slot->duration_us;
        pthread_cond_broadcast(&q->changed);
        ret = 0;
    } else if (!ret) ret = AVERROR(EAGAIN);
    pthread_mutex_unlock(&q->lock);
    return ret;
}

int packet_queue_pop(PacketQueue *q, AVPacket *empty) { return pop(q, empty, -1); }
int packet_queue_try_pop(PacketQueue *q, AVPacket *empty) { return pop(q, empty, 0); }
int packet_queue_pop_for(PacketQueue *q, AVPacket *empty, int64_t us) { return pop(q, empty, us); }

void packet_queue_finish(PacketQueue *q, int status) {
    pthread_mutex_lock(&q->lock);
    if (!q->terminal || q->terminal == AVERROR_EOF) q->terminal = status;
    pthread_cond_broadcast(&q->changed);
    pthread_mutex_unlock(&q->lock);
}

void packet_queue_destroy(PacketQueue *q) {
    for (unsigned i = 0; i < q->capacity; ++i) av_packet_free(&q->slots[i].packet);
    free(q->slots);
    pthread_cond_destroy(&q->changed);
    pthread_mutex_destroy(&q->lock);
}
