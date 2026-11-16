#pragma once
#include <libavutil/frame.h>
#include <libswscale/swscale.h>

#define VIDEO_FRAME_BUDGET (32u * 1024u * 1024u)

typedef struct RgbaRenderer {
    struct SwsContext *scale;
    uint8_t *scratch;
    size_t scratch_size, peak_bytes;
    int color_policy;
} RgbaRenderer;

// Width, height and stride describe the producer's currently locked buffer.
// Clockwise rotation uses the same convention as Android MediaFormat.
int rgba_render(RgbaRenderer *renderer, const AVFrame *frame, AVRational stream_sar,
                int stream_rotation, void *bits, int width, int height, int stride);
size_t rgba_frame_bytes(const AVFrame *frame);
void rgba_renderer_close(RgbaRenderer *renderer);
