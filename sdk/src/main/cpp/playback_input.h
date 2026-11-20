#pragma once
#include <libavformat/avformat.h>
#include <pthread.h>

#define PLAYBACK_HW_INIT FFERRTAG('H','W','I','N')
#define PLAYBACK_HW_RUNTIME FFERRTAG('H','W','R','T')

struct DecodeGroup;

// Single demux thread. Signal cancel_fd from the control thread, then join
// before destroying this object. Both descriptors are borrowed.
typedef struct PlaybackInput {
    int fd, cancel_fd, seekable;
    // After a PCR seek, video before the first random-access packet is undecodable.
    int wait_for_video_key;
    int64_t start, length, position, probe_remaining, deadline_us;
    // Sequential input can reread the contiguous prefix while the underlying cursor is inside it.
    uint8_t *prefix;
    int64_t source_position, probe_bytes;
    int prefix_size, probe_limit;
    int prefix_expired;
    int64_t operation_deadline_us;
    struct AudioHistory *audio_history;
    AVIOContext *io;
    AVFormatContext *format;
    pthread_mutex_t group_lock;
    struct DecodeGroup *active_group;
} PlaybackInput;

int playback_probe(PlaybackInput *input);
void playback_close(PlaybackInput *input);
int playback_sequential_pollers(void);
