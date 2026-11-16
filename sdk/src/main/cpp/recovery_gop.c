#include "recovery_gop.h"
#include <libavutil/avutil.h>
#include <libavutil/mathematics.h>
#include <libavutil/common.h>

void recovery_gop_clear(RecoveryGop *gop) {
    for (unsigned i = 0; i < gop->count; ++i) av_packet_free(&gop->packets[i]);
    gop->count = 0;
    gop->bytes = 0;
    gop->duration_us = 0;
}

void recovery_gop_remember(RecoveryGop *gop, const AVPacket *packet, AVRational time_base) {
    int64_t timestamp = packet->dts != AV_NOPTS_VALUE ? packet->dts : packet->pts;
    int64_t us = timestamp == AV_NOPTS_VALUE ? AV_NOPTS_VALUE :
            av_rescale_q(timestamp, time_base, (AVRational){1, 1000000});
    if (packet->flags & AV_PKT_FLAG_KEY) {
        recovery_gop_clear(gop);
        gop->first_us = us;
    } else if (!gop->count) return;
    const size_t limit = 8 * 1024 * 1024;
    size_t size = packet->size;
    for (int i = 0; i < packet->side_data_elems; ++i) {
        if (size > limit || packet->side_data[i].size > limit - size) {
            recovery_gop_clear(gop);
            return;
        }
        size += packet->side_data[i].size;
    }
    if (packet->duration > 0)
        gop->duration_us = av_sat_add64(gop->duration_us,
                av_rescale_q(packet->duration, time_base, (AVRational){1, 1000000}));
    if (size > limit - gop->bytes || gop->count == 512 || gop->duration_us > 2000000 ||
        (us != AV_NOPTS_VALUE && gop->first_us != AV_NOPTS_VALUE && av_sat_sub64(us, gop->first_us) > 2000000)) {
        recovery_gop_clear(gop);
        return;
    }
    AVPacket *copy = av_packet_clone(packet);
    if (!copy) { recovery_gop_clear(gop); return; }
    gop->packets[gop->count++] = copy;
    gop->bytes += size;
}
