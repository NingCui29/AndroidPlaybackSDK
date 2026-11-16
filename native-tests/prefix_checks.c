#include "playback_input.h"
#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>
#include <libavutil/time.h>

enum { PREFIX = 512 * 1024, TOTAL = PREFIX + 8192 };

typedef struct Feed {
    int fd, resume, finish;
} Feed;

static void *feed(void *arg) {
    Feed *source = arg;
    int fd = source->fd;
    uint8_t block[4096];
    for (int pos = 0; pos < TOTAL;) {
        for (unsigned i = 0; i < sizeof(block); ++i) block[i] = (pos + i) % 251;
        if (pos == 0) {
            // 48 kHz, mono 16-bit PCM WAV. Native-endian Linux arm64 is little endian.
            memcpy(block, "RIFF", 4);
            uint32_t size = TOTAL - 8;
            memcpy(block + 4, &size, 4);
            memcpy(block + 8, "WAVEfmt ", 8);
            uint32_t fmt[] = {16, 0x00010001, 48000, 96000, 0x00100002};
            memcpy(block + 16, fmt, sizeof(fmt));
            memcpy(block + 36, "data", 4);
            size = TOTAL - 44;
            memcpy(block + 40, &size, 4);
        }
        ssize_t n = write(fd, block, sizeof(block));
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) break;
        pos += n;
    }
    char byte;
    if (read(source->resume, &byte, 1) == 1) {
        usleep(250000);
        if (write(fd, &byte, 1) == 1) (void)read(source->finish, &byte, 1);
    }
    close(fd);
    return NULL;
}

static int checks(PlaybackInput *in, int resume, int finish) {
    // Use the actual callbacks installed by playback_probe on a real sequential input.
    int status = playback_probe(in);
    if (status < 0 || !in->format || in->format->nb_streams != 1 ||
            in->io->seekable || in->probe_bytes <= 0 || in->prefix_size <= 4096 ||
            in->source_position != in->probe_bytes) return 2;
    int64_t physical = in->source_position;
    uint8_t block[8192];
    in->probe_remaining = 123;
    if (in->io->seek(in, 44, SEEK_SET) != 44 ||
            in->io->read_packet(in, block, 4000) != 4000 || in->probe_remaining != 123) return 3;
    for (int i = 0; i < 4000; ++i) if (block[i] != (44 + i) % 251) return 4;
    if (in->io->seek(in, physical + 1, SEEK_SET) != AVERROR(ESPIPE) ||
            in->io->seek(in, physical, SEEK_SET) != physical) return 5;
    in->probe_remaining = -1;
    while (in->source_position < TOTAL) {
        int64_t start = in->position;
        int count = in->io->read_packet(in, block, sizeof(block));
        if (count <= 0) return 6;
        for (int i = 0; i < count; ++i) if (block[i] != (start + i) % 251) return 7;
    }
    if (in->prefix || in->prefix_size != 0 || !in->prefix_expired ||
            in->io->seek(in, 50, SEEK_SET) != AVERROR(ESPIPE)) return 8;
    // The pipe remains open. Spending longer than the stall budget without a read request
    // must leave the next read with its own budget, then new bytes must wake that read.
    sleep(11);
    if (write(resume, "R", 1) != 1) return 9;
    if (in->io->read_packet(in, block, 1) != 1 || block[0] != 'R') return 10;
    int64_t start = av_gettime_relative();
    int stalled = in->io->read_packet(in, block, 1);
    int64_t waited = av_gettime_relative() - start;
    if (stalled != AVERROR(ETIMEDOUT) || waited < 10000000) return 11;
    if (write(finish, "F", 1) != 1 || in->io->read_packet(in, block, 1) != AVERROR_EOF) return 12;
    printf("PASS no-demand idle=11s, resumed byte=R, continuous stall=%" PRId64
           " us raw=%d, writer close=EOF\n", waited, stalled);
    printf("PASS probe=%" PRId64 " prefixReread=4000 extraBudget=0 finalBytes=%d outsidePrefix=ESPIPE\n",
            physical, TOTAL);
    return 0;
}

int main(void) {
    int fds[2], resume[2], finish[2];
    if (pipe(fds) || pipe(resume) || pipe(finish)) return 1;
    signal(SIGPIPE, SIG_IGN);
    PlaybackInput input = {.fd=fds[0], .cancel_fd=-1, .length=-1};
    pthread_t writer;
    Feed source = {fds[1], resume[0], finish[0]};
    if (pthread_create(&writer, NULL, feed, &source)) return 1;
    int ret = checks(&input, resume[1], finish[1]);
    playback_close(&input);
    close(fds[0]);
    close(resume[1]);
    close(finish[1]);
    pthread_join(writer, NULL);
    close(resume[0]);
    close(finish[0]);
    if (ret) fprintf(stderr, "prefix check failed at %d\n", ret);
    return ret;
}
