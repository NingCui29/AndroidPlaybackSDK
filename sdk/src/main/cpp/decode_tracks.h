#pragma once
#include "playback_input.h"
#include "software_decoder.h"

typedef struct DecodeTarget {
    int stream;
    PlaybackFrameSink sink;
    void *opaque;
    int (*drain)(void *opaque);
    void (*abort)(void *opaque);
    // Optional compressed-input backend. NULL packet signals decoder EOS.
    int (*packet)(void *opaque, const AVPacket *packet);
    // Return 0 to discard the failed track and continue, or a negative session error.
    int (*failure)(void *opaque, int reason);
    // Finish a pending presentation before blocking on an empty input queue.
    int (*before_wait)(void *opaque);
    // One bounded audio lane admits interleaved alternate tracks for in-place selection.
    void (*observe_audio)(PlaybackInput *source, const AVPacket *packet);
    int64_t (*idle_wait_us)(void *opaque);
    // For indexed AVC in MP4, in stream ticks. Zero selects ordinary full decoding.
    int64_t avc_preroll_until;
} DecodeTarget;

// Called by the demux worker. Each target callback runs on its own worker.
// Targets and input must remain valid until the function joins all workers.
int playback_decode_tracks(PlaybackInput *input, const DecodeTarget *targets, int count);
