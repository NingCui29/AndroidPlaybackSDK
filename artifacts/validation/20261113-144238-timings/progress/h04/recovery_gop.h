#pragma once
#include <libavcodec/packet.h>

// Only complete keyframe-led groups can be replayed; eviction invalidates the whole group.
typedef struct RecoveryGop {
    AVPacket *packets[512];
    unsigned count;
    size_t bytes;
    int64_t first_us, duration_us;
} RecoveryGop;

void recovery_gop_remember(RecoveryGop *gop, const AVPacket *packet, AVRational time_base);
void recovery_gop_clear(RecoveryGop *gop);
