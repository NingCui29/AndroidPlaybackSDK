/* Compare packets leaving the SDK's per-track queues with independent ffprobe records. */
#include "decode_tracks.h"
#include <fcntl.h>
#include <inttypes.h>
#include <libavutil/mem.h>
#include <libavutil/sha.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct Reference {
    FILE *rows;
    struct AVSHA *sha;
    int index, count, eos;
} Reference;

static int compare_packet(void *opaque, const AVPacket *packet) {
    Reference *ref = opaque;
    char line[256];
    if (!packet) {
        ++ref->eos;
        return !fgets(line, sizeof(line), ref->rows) && feof(ref->rows) ? 0 : AVERROR_INVALIDDATA;
    }
    uint8_t hash[32];
    char hex[65], expected[65];
    int64_t pts, dts;
    int size, key;
    if (!fgets(line, sizeof(line), ref->rows) ||
            sscanf(line, "%" SCNd64 " %" SCNd64 " %d %d %64s", &pts, &dts, &size, &key, expected) != 5)
        return AVERROR_INVALIDDATA;
    av_sha_init(ref->sha, 256);
    av_sha_update(ref->sha, packet->data, packet->size);
    av_sha_final(ref->sha, hash);
    for (int i = 0; i < 32; ++i) snprintf(hex + 2*i, 3, "%02x", hash[i]);
    ++ref->count;
    if (packet->stream_index != ref->index || pts != packet->pts || dts != packet->dts ||
            size != packet->size || key != !!(packet->flags & AV_PKT_FLAG_KEY) || strcmp(hex, expected)) {
        fprintf(stderr, "Mismatch stream=%d packet=%d reference=%sactual=%" PRId64 " %" PRId64
                " %d %d %s\n", ref->index, ref->count, line, packet->pts, packet->dts,
                packet->size, !!(packet->flags & AV_PKT_FLAG_KEY), hex);
        return AVERROR_INVALIDDATA;
    }
    return 0;
}

int main(int argc, char **argv) {
    if (argc < 3 || argc > 4) return 2;
    struct stat st;
    PlaybackInput input = {.fd=open(argv[1], O_RDONLY), .cancel_fd=-1, .seekable=1};
    if (input.fd < 0 || fstat(input.fd, &st) < 0) return 2;
    input.length = st.st_size;
    Reference refs[2] = {0};
    DecodeTarget targets[2] = {0};
    int count = argc - 2;
    int ret = playback_probe(&input);
    for (int i = 0; ret >= 0 && i < count; ++i) {
        Reference *r = &refs[i];
        int num, den;
        char header[100];
        r->rows = fopen(argv[2+i], "r");
        r->sha = av_sha_alloc();
        if (!r->rows || !r->sha || !fgets(header, sizeof(header), r->rows) ||
                sscanf(header, "%d %d %d", &r->index, &num, &den) != 3 ||
                (unsigned)r->index >= input.format->nb_streams) { ret = AVERROR_INVALIDDATA; break; }
        AVRational tb = input.format->streams[r->index]->time_base;
        if (tb.num != num || tb.den != den) { ret = AVERROR_INVALIDDATA; break; }
        targets[i] = (DecodeTarget){.stream=r->index, .opaque=r, .packet=compare_packet};
    }
    if (ret >= 0) ret = playback_decode_tracks(&input, targets, count);
    for (int i = 0; i < count; ++i) {
        printf("%s stream=%d matched=%d eos=%d\n", argv[1], refs[i].index, refs[i].count, refs[i].eos);
        if (!refs[i].count || refs[i].eos != 1) ret = AVERROR_INVALIDDATA;
        if (refs[i].rows) fclose(refs[i].rows);
        av_free(refs[i].sha);
    }
    playback_close(&input);
    close(input.fd);
    printf("packet comparison %s ret=%d\n", ret >= 0 ? "PASS" : "FAIL", ret);
    return ret < 0;
}
