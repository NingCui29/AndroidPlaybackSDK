#pragma once
#include "playback_input.h"

// The demuxer supplies immutable packet references. Preparation cursors never consume its AVIO.
int audio_history_init(PlaybackInput *in);
void audio_history_record(PlaybackInput *in, const AVPacket *packet);
void audio_history_clear(PlaybackInput *in);
void audio_history_release(struct AudioHistory **slot);
