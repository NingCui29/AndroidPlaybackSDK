#include "rgba_renderer.h"
#include <libavutil/common.h>
#include <libavutil/display.h>
#include <libavutil/imgutils.h>
#include <libavutil/mem.h>
#include <libavutil/pixdesc.h>
#include <limits.h>
#include <math.h>
#include <string.h>

// Account for allocated row padding and shared plane owners, including slack at the
// end of decoder buffers. Frames passed here by libavcodec are reference counted.
size_t rgba_frame_bytes(const AVFrame *frame) {
    size_t size = 0;
    for (int i = 0; i < AV_NUM_DATA_POINTERS && frame->buf[i]; i++) {
        const AVBufferRef *ref = frame->buf[i];
        int first = 1;
        for (int j = 0; j < i; j++)
            if (ref->buffer == frame->buf[j]->buffer) first = 0;
        if (first) {
            if (ref->size > SIZE_MAX - size) return SIZE_MAX;
            size += ref->size;
        }
    }
    return size;
}

void rgba_renderer_close(RgbaRenderer *r) {
    sws_freeContext(r->scale);
    av_free(r->scratch);
    *r = (RgbaRenderer){0};
}

static int display_rotation(const AVFrame *frame, int degrees) {
    const AVFrameSideData *sd = av_frame_get_side_data(frame, AV_FRAME_DATA_DISPLAYMATRIX);
    if (sd && sd->size >= 9 * sizeof(int32_t)) {
        double angle = -av_display_rotation_get((const int32_t *)sd->data);
        if (isfinite(angle)) degrees = (int)lround(angle);
    }
    return (degrees % 360 + 360) % 360;
}

typedef struct Layout {
    int angle, w, h, x, y, rotated_w, rotated_h;
    double sine, cosine;
} Layout;

static Layout fit(int width, int height, AVRational sar, int angle, int screen_w, int screen_h) {
    double radians = angle * (3.14159265358979323846 / 180.0);
    double sine = sin(radians), cosine = cos(radians);
    // Avoid a spurious extra row/column at exact right angles.
    if (!(angle % 90)) {
        sine = angle == 90 ? 1 : angle == 270 ? -1 : 0;
        cosine = angle == 0 ? 1 : angle == 180 ? -1 : 0;
    }
    double sw = width * av_q2d(sar), sh = height;
    double rw = fabs(cosine) * sw + fabs(sine) * sh;
    double rh = fabs(sine) * sw + fabs(cosine) * sh;
    double k = fmin(screen_w / rw, screen_h / rh);
    Layout l = { .angle = angle, .sine = sine, .cosine = cosine };
    l.w = (int)fmax(1, floor(sw * k + 0.5));
    l.h = (int)fmax(1, floor(sh * k + 0.5));
    l.rotated_w = FFMIN(screen_w, (int)ceil(fabs(cosine) * l.w + fabs(sine) * l.h));
    l.rotated_h = FFMIN(screen_h, (int)ceil(fabs(sine) * l.w + fabs(cosine) * l.h));
    l.x = (screen_w - l.rotated_w) / 2;
    l.y = (screen_h - l.rotated_h) / 2;
    return l;
}

static void black(uint32_t *line, int n) {
    // Android arm64 stores RGBA8888 bytes in the order R, G, B, A.
    for (int x = 0; x < n; x++) line[x] = UINT32_C(0xff000000);
}

static void clear_borders(uint32_t *bits, int width, int height, int stride, Layout l) {
    for (int y = 0; y < height; y++) {
        uint32_t *line = bits + (size_t)y * stride;
        if (y < l.y || y >= l.y + l.rotated_h) black(line, width);
        else {
            black(line, l.x);
            black(line + l.x + l.rotated_w, width - l.x - l.rotated_w);
        }
    }
}

static void rotate(const uint32_t *src, int pitch, uint32_t *out, int stride, Layout l) {
    for (int y = 0; y < l.rotated_h; y++) {
        uint32_t *dst = out + (size_t)(l.y + y) * stride + l.x;
        if (l.angle == 90 || l.angle == 270 || l.angle == 180) {
            const uint32_t *s;
            int step;
            if (l.angle == 90) { s = src + (size_t)(l.h - 1) * pitch + y; step = -pitch; }
            else if (l.angle == 270) { s = src + l.w - 1 - y; step = pitch; }
            else { s = src + (size_t)(l.h - 1 - y) * pitch + l.w - 1; step = -1; }
            for (int x = 0; x < l.rotated_w; x++) dst[x] = s[(ptrdiff_t)x * step];
            continue;
        }
        for (int x = 0; x < l.rotated_w; x++) {
            double dx = x + .5 - .5 * l.rotated_w, dy = y + .5 - .5 * l.rotated_h;
            double sx = dx * l.cosine + dy * l.sine + .5 * l.w - .5;
            double sy = -dx * l.sine + dy * l.cosine + .5 * l.h - .5;
            if (sx < -.5 || sx >= l.w - .5 || sy < -.5 || sy >= l.h - .5) {
                black(dst + x, 1);
                continue;
            }
            sx = av_clipd(sx, 0, l.w - 1); sy = av_clipd(sy, 0, l.h - 1);
            int x0 = (int)sx, y0 = (int)sy, x1 = FFMIN(x0 + 1, l.w - 1), y1 = FFMIN(y0 + 1, l.h - 1);
            const uint8_t *top = (const uint8_t *)(src + (size_t)y0 * pitch);
            const uint8_t *bottom = (const uint8_t *)(src + (size_t)y1 * pitch);
            uint8_t *pixel = (uint8_t *)(dst + x);
            for (int c = 0; c < 4; c++) {
                double a = top[4*x0+c] + (sx-x0) * (top[4*x1+c] - top[4*x0+c]);
                double b = bottom[4*x0+c] + (sx-x0) * (bottom[4*x1+c] - bottom[4*x0+c]);
                pixel[c] = (uint8_t)(a + (sy-y0) * (b-a) + .5);
            }
        }
    }
}

static int configure_color(RgbaRenderer *r, const AVFrame *f, int visible_height) {
    const AVPixFmtDescriptor *desc = av_pix_fmt_desc_get(f->format);
    int inferred_matrix = 0, matrix;
    switch (f->colorspace) {
        case AVCOL_SPC_BT709: matrix = SWS_CS_ITU709; break;
        case AVCOL_SPC_FCC: matrix = SWS_CS_FCC; break;
        case AVCOL_SPC_BT470BG: case AVCOL_SPC_SMPTE170M: matrix = SWS_CS_ITU601; break;
        case AVCOL_SPC_SMPTE240M: matrix = SWS_CS_SMPTE240M; break;
        case AVCOL_SPC_BT2020_NCL: matrix = SWS_CS_BT2020; break;
        default: matrix = visible_height <= 576 ? SWS_CS_ITU601 : SWS_CS_ITU709; inferred_matrix = 1;
    }
    int inferred_range = f->color_range == AVCOL_RANGE_UNSPECIFIED;
    int full = f->color_range == AVCOL_RANGE_JPEG || (inferred_range && desc &&
               ((desc->flags & AV_PIX_FMT_FLAG_RGB) || !strncmp(desc->name, "yuvj", 4)));
    r->color_policy = matrix | (full << 8) | (inferred_range << 9) | (inferred_matrix << 10);
    const int *coeff = sws_getCoefficients(matrix);
    return sws_setColorspaceDetails(r->scale, coeff, full, coeff, 1, 0, 1 << 16, 1 << 16);
}

int rgba_render(RgbaRenderer *r, const AVFrame *frame, AVRational stream_sar,
                int stream_rotation, void *bits, int width, int height, int stride) {
    if (!bits || width <= 0 || height <= 0 || stride < width || stride > INT_MAX / 4 ||
        av_image_check_size(width, height, 0, NULL) < 0) return AVERROR(EINVAL);
    size_t frame_bytes = rgba_frame_bytes(frame);
    if (!frame_bytes || frame_bytes > VIDEO_FRAME_BUDGET) return AVERROR(ENOMEM);

    AVFrame visible = *frame;
    // Preserve exact edges; pass an unaligned cropped pointer only to the byte
    // copy below, never to the scaler's vectorized input kernels.
    int ret, needs_copy = 0;
    if (frame->crop_left || frame->crop_right || frame->crop_top || frame->crop_bottom) {
        ret = av_frame_apply_cropping(&visible, AV_FRAME_CROP_UNALIGNED);
        if (ret < 0) return ret;
        for (int i = 0; i < 4 && visible.data[i]; i++)
            if (visible.data[i] != frame->data[i] && ((uintptr_t)visible.data[i] & 31)) needs_copy = 1;
    }
    AVRational sar = frame->sample_aspect_ratio;
    if (sar.num <= 0 || sar.den <= 0) sar = stream_sar;
    if (sar.num <= 0 || sar.den <= 0) sar = (AVRational){1,1};
    Layout l = fit(visible.width, visible.height, sar, display_rotation(frame, stream_rotation), width, height);
    int copy_bytes = needs_copy ? av_image_get_buffer_size(visible.format, visible.width, visible.height, 32) : 0;
    int rotate_bytes = l.angle ? av_image_get_buffer_size(AV_PIX_FMT_RGBA, l.w, l.h, 32) : 0;
    if (copy_bytes < 0 || rotate_bytes < 0) return AVERROR(ENOMEM);
    size_t copy_storage = copy_bytes ? FFALIGN((size_t)copy_bytes, 32) + 64 : 0;
    size_t needed = copy_storage + (rotate_bytes ? (size_t)rotate_bytes + 64 : 0);
    if (needed > VIDEO_FRAME_BUDGET - frame_bytes) return AVERROR(ENOMEM);
    if (r->scratch_size < needed || r->scratch_size > VIDEO_FRAME_BUDGET - frame_bytes) {
        av_freep(&r->scratch);
        r->scratch_size = 0;
        if (needed) {
            r->scratch = av_mallocz(needed);
            if (!r->scratch) return AVERROR(ENOMEM);
            r->scratch_size = needed;
        }
    }
    r->peak_bytes = FFMAX(r->peak_bytes, frame_bytes + r->scratch_size);
    if (needs_copy) {
        uint8_t *aligned[4];
        int lines[4];
        ret = av_image_fill_arrays(aligned, lines, r->scratch, visible.format, visible.width, visible.height, 32);
        if (ret < 0) return ret;
        av_image_copy(aligned, lines, (const uint8_t **)visible.data, visible.linesize,
                      visible.format, visible.width, visible.height);
        memcpy(visible.data, aligned, sizeof(aligned));
        memcpy(visible.linesize, lines, sizeof(lines));
    }
    r->scale = sws_getCachedContext(r->scale, visible.width, visible.height, visible.format,
            l.w, l.h, AV_PIX_FMT_RGBA, SWS_FAST_BILINEAR, NULL, NULL, NULL);
    if (!r->scale) return AVERROR(ENOMEM);
    ret = configure_color(r, frame, visible.height);
    if (ret < 0) return ret;
    int dst_stride = l.angle ? FFALIGN(l.w * 4, 32) : stride * 4;
    uint8_t *dst[4] = {l.angle ? r->scratch + copy_storage :
                        (uint8_t *)((uint32_t *)bits + (size_t)l.y * stride + l.x)};
    int strides[4] = {dst_stride};
    int lines = sws_scale(r->scale, (const uint8_t *const *)visible.data, visible.linesize,
                          0, visible.height, dst, strides);
    if (lines != l.h) return lines < 0 ? lines : AVERROR_EXTERNAL;
    if (l.angle) rotate((const uint32_t *)dst[0], dst_stride / 4, bits, stride, l);
    clear_borders(bits, width, height, stride, l);
    return 0;
}
