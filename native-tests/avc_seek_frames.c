/* Compare full decode with the production AVC preroll decoder over an indexed MP4. */
#include "playback_input.h"
#include "software_decoder.h"
#include <fcntl.h>
#include <inttypes.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/sha.h>
#include <stdio.h>
#include <sys/stat.h>
#include <unistd.h>

typedef struct Frames {
    struct AVSHA *sha;
    int64_t target, first;
    int skipped, kept;
} Frames;

static int compare_frame(void *opaque, const AVFrame *f) {
    Frames *frames = opaque;
    int64_t pts = f->best_effort_timestamp;
    if (pts == AV_NOPTS_VALUE || f->duration <= 0) return AVERROR_INVALIDDATA;
    if (pts + f->duration <= frames->target) { ++frames->skipped; return 0; }
    if (!frames->kept++) frames->first = pts;
    int n = av_image_get_buffer_size(f->format, f->width, f->height, 1);
    if (n < 0) return n;
    uint8_t *pixels = av_malloc(n);
    if (!pixels) return AVERROR(ENOMEM);
    int ret = av_image_copy_to_buffer(pixels, n, (const uint8_t *const *)f->data, f->linesize,
                                      f->format, f->width, f->height, 1);
    if (ret == n) {
        av_sha_update(frames->sha, (uint8_t *)&pts, sizeof(pts));
        av_sha_update(frames->sha, pixels, n);
    }
    av_free(pixels);
    return ret == n ? 0 : AVERROR_INVALIDDATA;
}

int main(int argc, char **argv) {
    if (argc != 3) return 2;
    int ms = atoi(argv[2]);
    uint8_t hashes[2][32];
    Frames frames[2] = {0};
    for (int mode = 0; mode < 2; ++mode) {
        struct stat st;
        PlaybackInput input = {.fd=open(argv[1], O_RDONLY), .cancel_fd=-1, .seekable=1};
        if (input.fd < 0 || fstat(input.fd, &st)) return 2;
        input.length = st.st_size;
        if (playback_probe(&input) < 0) return 2;
        int index = av_find_best_stream(input.format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
        if (index < 0) return 2;
        AVStream *stream = input.format->streams[index];
        Frames *f = &frames[mode];
        f->target = av_rescale_q_rnd(ms, (AVRational){1,1000}, stream->time_base, AV_ROUND_DOWN);
        SoftwareDecoder decoder = {.avc_preroll_until=mode ? f->target : 0};
        AVPacket *packet = av_packet_alloc();
        f->sha = av_sha_alloc();
        if (!packet || !f->sha || av_sha_init(f->sha, 256) ||
                avformat_seek_file(input.format, index, INT64_MIN, f->target, f->target, 0) < 0 ||
                software_decoder_open(&decoder, stream) < 0) return 2;
        int ret;
        while ((ret = av_read_frame(input.format, packet)) >= 0) {
            if (packet->stream_index == index)
                ret = software_decoder_submit(&decoder, packet, compare_frame, f);
            av_packet_unref(packet);
            if (ret < 0) return 2;
        }
        if (ret != AVERROR_EOF || software_decoder_submit(&decoder, NULL, compare_frame, f) || !f->kept)
            return 2;
        av_sha_final(f->sha, hashes[mode]);
        av_free(f->sha);
        av_packet_free(&packet);
        software_decoder_close(&decoder);
        playback_close(&input);
        close(input.fd);
    }
    int ok = frames[0].first == frames[1].first && frames[0].kept == frames[1].kept &&
            frames[1].skipped < frames[0].skipped && !memcmp(hashes[0], hashes[1], 32);
    printf("targetMs=%d preFrames=%d/%d outputFrames=%d/%d firstPTS=%" PRId64 "/%" PRId64
           " pixelAndPtsSHA256Equal=%d verdict=%s\n", ms, frames[0].skipped, frames[1].skipped,
           frames[0].kept, frames[1].kept, frames[0].first, frames[1].first,
           !memcmp(hashes[0], hashes[1], 32), ok ? "PASS" : "FAIL");
    return !ok;
}
