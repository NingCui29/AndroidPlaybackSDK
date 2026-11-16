#pragma once
#include <libavcodec/packet.h>
#include <pthread.h>

// One producer and one consumer per selected stream. Destroy after both join.
typedef struct QueuedPacket {
    AVPacket *packet;
    size_t bytes;
    int64_t duration_us;
} QueuedPacket;

typedef struct PacketQueue {
    pthread_mutex_t lock;
    pthread_cond_t changed;
    QueuedPacket *slots;
    unsigned capacity, head, count;
    size_t bytes, byte_limit, single_limit;
    int64_t duration_us;
    int throttled, terminal;
} PacketQueue;

int packet_queue_init(PacketQueue *q, int video);
// Success transfers packet contents. Failure leaves them with the caller.
int packet_queue_push(PacketQueue *q, AVPacket *packet, AVRational time_base);
int packet_queue_pop(PacketQueue *q, AVPacket *empty);
int packet_queue_try_pop(PacketQueue *q, AVPacket *empty);
// AVERROR(EAGAIN) on a monotonic timeout. Negative duration waits until input or termination.
int packet_queue_pop_for(PacketQueue *q, AVPacket *empty, int64_t us);
// EOF drains pending packets. Other negative statuses interrupt immediately.
void packet_queue_finish(PacketQueue *q, int status);
void packet_queue_destroy(PacketQueue *q);
