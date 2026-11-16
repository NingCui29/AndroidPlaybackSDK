/* I01: exercise the production AVIO callbacks across 2 GiB and 4 GiB on arm64. */
#include "playback_input.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int check_range(int host, int64_t start) {
    /* A mono PCM WAV with one four-byte packet, bracketed by non-media bytes. */
    uint8_t bytes[] = {
        0xab,'R','I','F','F',40,0,0,0,'W','A','V','E','f','m','t',' ',
        16,0,0,0,1,0,1,0,0x80,0xbb,0,0,0,0x77,1,0,2,0,16,0,
        'd','a','t','a',4,0,0,0,1,2,3,4,0xcd
    };
    const int size = sizeof(bytes) - 2;
    if (pwrite(host, bytes, sizeof(bytes), start - 1) != sizeof(bytes) ||
            lseek(host, 17, SEEK_SET) != 17) return 1;
    PlaybackInput in = {.fd=dup(host), .cancel_fd=-1, .seekable=1, .start=start, .length=size};
    int rc = 2;
    if (in.fd < 0 || playback_probe(&in) < 0 || in.format->nb_streams != 1) goto done;
    uint8_t output[sizeof(bytes)];
    AVIOContext *io = in.io;
    rc = 3;
    if (io->seek(&in, 0, AVSEEK_SIZE) != size || io->seek(&in, 0, SEEK_SET) != 0 ||
            io->read_packet(&in, output, sizeof(output)) != size ||
            memcmp(output, bytes + 1, size) || io->read_packet(&in, output, 1) != AVERROR_EOF) goto done;
    rc = 4;
    if (io->seek(&in, -1, SEEK_END) != size-1 || io->seek(&in, 1, SEEK_END) >= 0 ||
            io->seek(&in, -1, SEEK_SET) >= 0 || io->seek(&in, INT64_MAX, SEEK_CUR) >= 0 ||
            io->read_packet(&in, output, 2) != 1 || output[0] != 4 ||
            lseek(host, 0, SEEK_CUR) != 17) goto done;
    rc = 0;
    printf("PASS start=%" PRId64 " end=%" PRId64 " bytes=%d hostCursor=17\n", start, start+size, size);
done:
    playback_close(&in);
    if (in.fd >= 0) close(in.fd);
    return rc;
}

int main(int argc, char **argv) {
    if (argc != 2) return 1;
    int fd = open(argv[1], O_RDWR | O_CREAT | O_EXCL, 0600);
    if (fd < 0) return 1;
    unlink(argv[1]);
    int rc = check_range(fd, (INT64_C(1)<<31)-24);
    if (!rc) rc = check_range(fd, (INT64_C(1)<<32)-24);
    close(fd);
    if (rc) fprintf(stderr, "range check failed at %d\n", rc);
    return rc;
}
