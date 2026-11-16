/* Probe valid, unknown-length PCM and a sequential WAV whose header exceeds the I03 byte budget. */
#include "playback_input.h"
#include <errno.h>
#include <inttypes.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

typedef struct Writer { int fd, oversized; } Writer;

static int write_bytes(int fd, const void *data, size_t len) {
    const uint8_t *p = data;
    while (len) {
        ssize_t n = write(fd, p, len);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return -1;
        p += n;
        len -= (size_t)n;
    }
    return 0;
}

static void *feed(void *arg) {
    Writer *w = arg;
    static const uint8_t header[] = {
        'R','I','F','F',255,255,255,255,'W','A','V','E','f','m','t',' ',
        16,0,0,0, 1,0, 1,0, 128,187,0,0, 0,119,1,0, 2,0,16,0
    };
    static const uint8_t data_chunk[] = {'d','a','t','a',255,255,255,255};
    static const uint8_t junk[] = {'J','U','N','K',0,0,96,0};
    uint8_t block[8192] = {0};
    if (write_bytes(w->fd, header, sizeof(header)) < 0) goto done;
    if (w->oversized) {
        if (write_bytes(w->fd, junk, sizeof(junk)) < 0) goto done;
        for (int i = 0; i < (6 * 1024 * 1024) / sizeof(block); ++i)
            if (write_bytes(w->fd, block, sizeof(block)) < 0) goto done;
    }
    if (write_bytes(w->fd, data_chunk, sizeof(data_chunk)) >= 0)
        write_bytes(w->fd, block, sizeof(block));
done:
    close(w->fd);
    return NULL;
}

static int check(int oversized) {
    int fds[2];
    if (pipe(fds)) return 1;
    Writer w = {.fd=fds[1], .oversized=oversized};
    PlaybackInput input = {.fd=fds[0], .cancel_fd=-1, .length=-1};
    pthread_t thread;
    if (pthread_create(&thread, NULL, feed, &w)) return 1;
    int ret = playback_probe(&input);
    int ok;
    if (oversized) {
        ok = ret == AVERROR(ENOBUFS) && input.probe_bytes == 5 * 1024 * 1024 &&
                input.probe_limit == ret;
        printf("probe header bytes=%" PRId64 " raw=%d limit=%d\n",
               input.probe_bytes, ret, input.probe_limit);
    } else {
        ok = ret == 0 && input.format->nb_streams == 1 &&
                input.format->duration == AV_NOPTS_VALUE &&
                input.format->streams[0]->codecpar->codec_id == AV_CODEC_ID_PCM_S16LE &&
                !input.io->seekable;
        printf("unknown-duration WAV probe=%d unknown=%d seekable=%d\n", ret,
               ret == 0 && input.format->duration == AV_NOPTS_VALUE, input.io->seekable);
    }
    playback_close(&input);
    close(fds[0]);
    pthread_join(thread, NULL);
    return !ok;
}

int main(void) {
    signal(SIGPIPE, SIG_IGN);
    int failed = check(0);
    failed |= check(1);
    printf("probe bounds %s\n", failed ? "FAIL" : "PASS");
    return failed;
}
