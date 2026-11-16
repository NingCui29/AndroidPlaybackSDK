#pragma once
#include <libavcodec/avcodec.h>
#include <libavformat/avformat.h>

// One instance per selected stream, owned by its decode worker.
typedef struct SoftwareDecoder {
    AVCodecContext *codec;
    AVFrame *frame;
    int64_t avc_preroll_until;
} SoftwareDecoder;

// The callback borrows the frame until return. Retain with av_frame_ref if queued.
typedef int (*PlaybackFrameSink)(void *opaque, const AVFrame *frame);
int software_decoder_open(SoftwareDecoder *decoder, const AVStream *stream);
int software_decoder_open_config(SoftwareDecoder *decoder, const AVStream *stream,
                                 const uint8_t *extra, int extra_size);
int software_decoder_submit(SoftwareDecoder *decoder, const AVPacket *packet,
                            PlaybackFrameSink sink, void *opaque);
void software_decoder_close(SoftwareDecoder *decoder);
