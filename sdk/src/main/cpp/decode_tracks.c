#include "decode_tracks.h"
#include "packet_queue.h"
#include <stdatomic.h>

typedef struct DecodeGroup DecodeGroup;
typedef struct DecodeWorker {
    DecodeGroup *group;
    DecodeTarget target;
    PacketQueue queue;
    SoftwareDecoder decoder;
    pthread_t thread;
    AVRational time_base;
    int result;
    int discarded;
} DecodeWorker;

struct DecodeGroup {
    DecodeWorker workers[2];
    int initialized;
    atomic_int failure;
};

static void finish_all(DecodeGroup *group, int status) {
    if (status != AVERROR_EOF) {
        int empty = 0;
        if (!atomic_compare_exchange_strong(&group->failure, &empty, status)) return;
        for (int i = 0; i < group->initialized; ++i) {
            DecodeTarget *target = &group->workers[i].target;
            if (target->abort) target->abort(target->opaque);
        }
    }
    for (int i = 0; i < group->initialized; ++i)
        packet_queue_finish(&group->workers[i].queue, status);
}

static int track_result(DecodeWorker *worker, int ret) {
    if (ret >= 0 || ret == AVERROR_EXIT || !worker->target.failure) return ret;
    ret = worker->target.failure(worker->target.opaque, ret);
    if (ret >= 0) {
        worker->discarded = 1;
        software_decoder_close(&worker->decoder);
    }
    return ret;
}

static void *consume(void *opaque) {
    DecodeWorker *worker = opaque;
    AVPacket *packet = av_packet_alloc();
    int ret = packet ? 0 : AVERROR(ENOMEM);
    while (ret >= 0) {
        if (worker->target.before_wait && !worker->discarded) {
            ret = packet_queue_try_pop(&worker->queue, packet);
            if (ret == AVERROR(EAGAIN)) {
                ret = track_result(worker, worker->target.before_wait(worker->target.opaque));
                if (ret < 0) break;
                int64_t wait_us = worker->target.idle_wait_us && !worker->discarded
                        ? worker->target.idle_wait_us(worker->target.opaque) : -1;
                ret = packet_queue_pop_for(&worker->queue, packet, wait_us);
                if (ret == AVERROR(EAGAIN)) { ret = 0; continue; }
            }
        } else ret = packet_queue_pop(&worker->queue, packet);
        if (ret == AVERROR_EOF) {
            if (worker->discarded) { ret = 0; break; }
            ret = worker->target.packet ? worker->target.packet(worker->target.opaque, NULL) :
                    software_decoder_submit(&worker->decoder, NULL, worker->target.sink, worker->target.opaque);
            if (ret >= 0 && worker->target.drain) ret = worker->target.drain(worker->target.opaque);
            ret = track_result(worker, ret);
            break;
        }
        if (ret < 0) break;
        if (!worker->discarded) {
            ret = worker->target.packet ? worker->target.packet(worker->target.opaque, packet) :
                    software_decoder_submit(&worker->decoder, packet, worker->target.sink, worker->target.opaque);
            ret = track_result(worker, ret);
        }
        av_packet_unref(packet);
    }
    worker->result = ret;
    if (ret < 0) finish_all(worker->group, ret);
    av_packet_free(&packet);
    return NULL;
}

int playback_decode_tracks(PlaybackInput *input, const DecodeTarget *targets, int count) {
    if (count < 1 || count > 2) return AVERROR(EINVAL);
    DecodeGroup group = {0};
    AVPacket *packet = av_packet_alloc();
    int started = 0, ret = packet ? 0 : AVERROR(ENOMEM);
    for (int i = 0; ret >= 0 && i < count; ++i) {
        DecodeWorker *worker = &group.workers[i];
        worker->group = &group;
        worker->target = targets[i];
        int index = targets[i].stream;
        if ((unsigned)index >= input->format->nb_streams || (!targets[i].sink && !targets[i].packet) ||
            (i && index == targets[0].stream)) { ret = AVERROR(EINVAL); break; }
        AVStream *stream = input->format->streams[index];
        worker->time_base = stream->time_base;
        ret = packet_queue_init(&worker->queue, stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO);
        if (ret < 0) break;
        group.initialized++;
        worker->decoder.avc_preroll_until = targets[i].avc_preroll_until;
        if (!worker->target.packet) ret = track_result(worker, software_decoder_open(&worker->decoder, stream));
    }
    while (ret >= 0 && started < count) {
        DecodeWorker *worker = &group.workers[started];
        ret = -pthread_create(&worker->thread, NULL, consume, worker);
        if (!ret) started++;
    }
    while (ret >= 0) {
        ret = av_read_frame(input->format, packet);
        if (ret < 0) break;
        for (int i = 0; i < count; ++i) {
            DecodeWorker *worker = &group.workers[i];
            AVStream *stream = input->format->streams[packet->stream_index];
            if (worker->target.observe_audio && stream->codecpar->codec_type == AVMEDIA_TYPE_AUDIO)
                worker->target.observe_audio(input, packet);
            else if (packet->stream_index != worker->target.stream) continue;
            if (input->wait_for_video_key && input->format->streams[packet->stream_index]->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                if (!(packet->flags & AV_PKT_FLAG_KEY)) break;
                input->wait_for_video_key = 0;
            }
            ret = packet_queue_push(&worker->queue, packet, stream->time_base);
            break;
        }
        av_packet_unref(packet);
    }
    finish_all(&group, ret);
    if (ret == AVERROR_EOF) ret = 0;
    for (int i = 0; i < started; ++i) {
        pthread_join(group.workers[i].thread, NULL);
        if ((ret >= 0 || ret == AVERROR_EXIT) && group.workers[i].result < 0) ret = group.workers[i].result;
    }
    for (int i = 0; i < group.initialized; ++i) {
        software_decoder_close(&group.workers[i].decoder);
        packet_queue_destroy(&group.workers[i].queue);
    }
    av_packet_free(&packet);
    return group.failure ? group.failure : ret;
}
