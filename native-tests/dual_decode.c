#include "decode_tracks.h"
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>

typedef struct Counts { int64_t samples, frames, last_pts; } Counts;
static int record(void *opaque, const AVFrame *frame) {
    Counts *c = opaque;
    if (frame->best_effort_timestamp == AV_NOPTS_VALUE ||
        (c->frames && frame->best_effort_timestamp <= c->last_pts)) return AVERROR_INVALIDDATA;
    c->last_pts = frame->best_effort_timestamp;
    c->samples += frame->nb_samples;
    c->frames++;
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2) return 1;
    int fd = open(argv[1], O_RDONLY);
    struct stat st;
    if (fd < 0 || fstat(fd, &st)) return 2;
    PlaybackInput in = {.fd=fd, .cancel_fd=-1, .seekable=1, .length=st.st_size};
    Counts audio = {0}, video = {0};
    int ret = playback_probe(&in);
    if (ret >= 0) {
        DecodeTarget targets[] = {
            {av_find_best_stream(in.format, AVMEDIA_TYPE_AUDIO,-1,-1,NULL,0), record, &audio},
            {av_find_best_stream(in.format, AVMEDIA_TYPE_VIDEO,-1,-1,NULL,0), record, &video}
        };
        ret = playback_decode_tracks(&in, targets, 2);
    }
    printf("V01 status=%d video_frames=%lld audio_frames=%lld samples=%lld\n", ret,
           (long long)video.frames, (long long)audio.frames, (long long)audio.samples);
    playback_close(&in);
    close(fd);
    return ret || video.frames != 18000 || audio.samples < 28800000 ||
           audio.samples > 28800000 + 2048;
}
