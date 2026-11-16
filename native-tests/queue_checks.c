#include "packet_queue.h"
#include <assert.h>
#include <libavutil/error.h>
#include <stdatomic.h>
#include <stdio.h>
#include <unistd.h>

typedef struct Push { PacketQueue *q; atomic_int entered, done; int result; } Push;
static void *push(void *arg) {
    Push *job = arg;
    AVPacket *p = av_packet_alloc();
    atomic_store(&job->entered, 1);
    job->result = packet_queue_push(job->q, p, (AVRational){1, 1});
    atomic_store(&job->done, 1);
    av_packet_free(&p);
    return NULL;
}

int main(void) {
    for (int video = 0; video <= 1; ++video) {
        PacketQueue q;
        assert(packet_queue_init(&q, video) == 0);
        AVPacket *p = av_packet_alloc();
        assert(av_new_packet(p, q.single_limit) == 0);
        assert(av_packet_new_side_data(p, AV_PKT_DATA_NEW_EXTRADATA, 1));
        assert(packet_queue_push(&q, p, (AVRational){1, 1}) == AVERROR(ENOBUFS));
        av_packet_unref(p);
        for (unsigned i = 0; i < q.capacity; ++i) {
            p->pts = i;
            assert(packet_queue_push(&q, p, (AVRational){1, 1}) == 0);
        }
        Push job = {.q=&q};
        pthread_t thread;
        assert(!pthread_create(&thread, NULL, push, &job));
        while (!atomic_load(&job.entered)) usleep(100);
        usleep(10000);
        assert(!atomic_load(&job.done));
        packet_queue_finish(&q, AVERROR_EXIT);
        pthread_join(thread, NULL);
        assert(job.result == AVERROR_EXIT);
        assert(packet_queue_pop(&q, p) == AVERROR_EXIT);
        packet_queue_destroy(&q);
        assert(packet_queue_init(&q, video) == 0);
        p->duration = 3;
        assert(packet_queue_push(&q, p, (AVRational){1, 1}) == 0);
        packet_queue_finish(&q, AVERROR_EOF);
        assert(packet_queue_pop(&q, p) == 0 && p->duration == 3);
        assert(packet_queue_pop(&q, p) == AVERROR_EOF);
        packet_queue_destroy(&q);
        av_packet_free(&p);
    }
    puts("audio/video: side-data limit, full queue cancellation, oversized duration, EOF drain passed");
}
