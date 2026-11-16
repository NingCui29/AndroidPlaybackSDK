#include "playback_input.h"
#include <fcntl.h>
#include <stdio.h>
#include <unistd.h>
#include <sys/stat.h>

// Test fixture: media surrounded by four leading and four trailing sentinel bytes.
int main(int argc, char **argv) {
    if (argc != 2) return 1;
    int fd = open(argv[1], O_RDONLY);
    struct stat st;
    if (fd < 0 || fstat(fd, &st) || st.st_size <= 8) return 2;
    PlaybackInput input = {.fd=fd, .cancel_fd=-1, .seekable=1, .start=4, .length=st.st_size-8};
    int ret = playback_probe(&input);
    AVPacket *packet = av_packet_alloc();
    if (!packet) return 3;
    int count = 0;
    if (ret >= 0) {
        while ((ret = av_read_frame(input.format, packet)) >= 0) {
            count++;
            av_packet_unref(packet);
        }
        printf("streams=%u packets=%d eof=%d host_offset=%lld\n", input.format->nb_streams,
               count, ret == AVERROR_EOF, (long long)lseek(fd, 0, SEEK_CUR));
    }
    int ok = count > 0 && ret == AVERROR_EOF && lseek(fd, 0, SEEK_CUR) == 0;
    av_packet_free(&packet);
    playback_close(&input);
    close(fd);
    return !ok;
}
