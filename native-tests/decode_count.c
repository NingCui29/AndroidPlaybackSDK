#include "software_decoder.h"
#include "playback_input.h"
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sys/stat.h>

static int count_output(void *opaque, const AVFrame *frame) {
    int64_t *count = opaque;
    *count += frame->nb_samples ? frame->nb_samples : 1;
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 3) return 1;
    int fd = open(argv[1], O_RDONLY);
    struct stat stat;
    if (fd < 0 || fstat(fd, &stat)) return 2;
    PlaybackInput input = {.fd=fd, .cancel_fd=-1, .seekable=1, .length=stat.st_size};
    SoftwareDecoder decoder = {0};
    AVPacket *packet = av_packet_alloc();
    int64_t count = 0;
    int ret = playback_probe(&input);
    if (ret >= 0 && packet && input.format->nb_streams == 1) {
        ret = software_decoder_open(&decoder, input.format->streams[0]);
        while (ret >= 0) {
            ret = av_read_frame(input.format, packet);
            if (ret < 0) break;
            ret = software_decoder_submit(&decoder, packet, count_output, &count);
            av_packet_unref(packet);
        }
        if (ret == AVERROR_EOF)
            ret = software_decoder_submit(&decoder, NULL, count_output, &count);
    }
    printf("decoded=%lld expected=%s status=%d\n", (long long)count, argv[2], ret);
    software_decoder_close(&decoder);
    av_packet_free(&packet);
    playback_close(&input);
    close(fd);
    return ret != 0 || count != atoll(argv[2]);
}
