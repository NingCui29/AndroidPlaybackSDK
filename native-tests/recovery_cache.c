/* Exercise the candidate's sequential H04 recovery cache, including loss of its keyframe. */
#include "recovery_gop.h"
#include <libavutil/avutil.h>
#include <stdio.h>

static int push(RecoveryGop *gop, int key, int size, int side, int64_t ts, int64_t duration) {
    AVPacket *p = av_packet_alloc();
    if (!p || av_new_packet(p, size) < 0) return 0;
    p->flags = key ? AV_PKT_FLAG_KEY : 0;
    p->dts = p->pts = ts;
    p->duration = duration;
    if (side && !av_packet_new_side_data(p, AV_PKT_DATA_NEW_EXTRADATA, side)) return 0;
    if (size) p->data[0] = key ? 0x65 : 0x41;
    recovery_gop_remember(gop, p, (AVRational){1,1000000});
    av_packet_free(&p);
    return 1;
}

#define REQUIRE(test) do { if (!(test)) { fprintf(stderr,"H04 assertion line=%d\n",__LINE__); return 1; } } while (0)

int main(void) {
    RecoveryGop g = {0};
    const int limit = 8 * 1024 * 1024;
    REQUIRE(push(&g, 0, 2, 0, 0, 0) && !g.count);
    REQUIRE(push(&g, 1, limit-4, 4, 0, 0) && g.count == 1 && g.bytes == limit);
    REQUIRE(g.packets[0]->side_data_elems == 1 && g.packets[0]->data[0] == 0x65);
    REQUIRE(push(&g, 0, 1, 0, 1, 0) && !g.count && !g.bytes);
    REQUIRE(push(&g, 0, 1, 0, 2, 0) && !g.count);
    REQUIRE(push(&g, 1, 1, 0, 0, 0));
    REQUIRE(push(&g, 0, 1, 0, 2000000, 0) && g.count == 2);
    REQUIRE(push(&g, 0, 1, 0, 2000001, 0) && !g.count);
    REQUIRE(push(&g, 1, 1, 0, AV_NOPTS_VALUE, 1000000));
    REQUIRE(push(&g, 0, 1, 0, AV_NOPTS_VALUE, 1000000) && g.duration_us == 2000000 && g.count == 2);
    REQUIRE(push(&g, 0, 1, 0, AV_NOPTS_VALUE, 1) && g.count == 0);
    REQUIRE(push(&g, 1, 1, 0, 0, 0));
    for (int i=1; i<512; ++i) REQUIRE(push(&g, 0, 1, 0, i, 0));
    REQUIRE(g.count == 512 && push(&g, 0, 1, 0, 512, 0) && !g.count);
    REQUIRE(push(&g, 1, 5, 0, 2700000, 1000));
    REQUIRE(push(&g, 0, 2, 0, 2800000, 1000));
    REQUIRE(push(&g, 1, 4, 0, 3000000, 1000));
    REQUIRE(g.count == 1 && g.bytes == 4 && g.first_us == 3000000 && g.duration_us == 1000);
    recovery_gop_clear(&g);
    REQUIRE(!g.count && !g.duration_us && !g.bytes);
    puts("H04 cache PASS unkeyed, cloned packet and side data, byte limit, DTS span, missing timestamps, count limit, new key, clear");
    return 0;
}
