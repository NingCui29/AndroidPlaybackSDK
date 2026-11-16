// Links against the same rgba_render implementation as the public Android player.
#include "rgba_renderer.h"
#include <libavutil/display.h>
#include <assert.h>
#include <math.h>
#include <stdio.h>
#include <string.h>

static AVFrame *image(enum AVPixelFormat fmt, int w, int h) {
    AVFrame *f = av_frame_alloc();
    assert(f);
    f->format = fmt; f->width = w; f->height = h;
    assert(av_frame_get_buffer(f, 64) == 0);
    return f;
}

static int component(uint32_t pixel, int channel) { return (pixel >> (channel * 8)) & 255; }

// Independent ITU-R Y'CbCr equations, no libswscale coefficients in the oracle.
static void color(RgbaRenderer *r, int height, int tagged, int full, int *max_error) {
    AVFrame *f = image(AV_PIX_FMT_YUV420P, 92, height);
    const int y[4] = {16, 235, 100, 140}, u[4] = {128, 128, 90, 164}, v[4] = {128, 128, 168, 90};
    for (int p = 0; p < 3; p++) {
        int n = p ? 46 : 92, rows = p ? (height + 1)/2 : height;
        for (int i = 0; i < rows; i++)
            for (int x = 0; x < n; x++)
                f->data[p][(size_t)i*f->linesize[p] + x] = (p == 0 ? y : p == 1 ? u : v)[x*4/n];
    }
    int hd = tagged ? height < 576 : height > 576;
    f->colorspace = tagged ? (hd ? AVCOL_SPC_BT709 : AVCOL_SPC_SMPTE170M) : AVCOL_SPC_UNSPECIFIED;
    f->color_range = full ? AVCOL_RANGE_JPEG : tagged ? AVCOL_RANGE_MPEG : AVCOL_RANGE_UNSPECIFIED;
    uint32_t pixels[40*98];
    for (unsigned i = 0; i < sizeof(pixels)/sizeof(*pixels); i++) pixels[i] = 0x12345678;
    // Non-square SAR gives a 92:36 visible rectangle inside 96:40.
    assert(rgba_render(r, f, (AVRational){height,36}, 0, pixels, 96, 40, 98) == 0);
    for (int i = 0; i < 4; i++) {
        double kr = hd ? .2126 : .299, kb = hd ? .0722 : .114;
        double yy = full ? y[i] : (y[i] - 16)*255./219;
        double cb = (u[i]-128)*(full ? 1. : 255./224), cr = (v[i]-128)*(full ? 1. : 255./224);
        double red = yy + 2*(1-kr)*cr, blue = yy + 2*(1-kb)*cb;
        double rgb[3] = {red,(yy-kr*red-kb*blue)/(1-kr-kb),blue};
        uint32_t px = pixels[20*98 + 12 + 24*i];
        for (int c = 0; c < 3; c++) {
            int diff = abs(component(px, c) - (int)lround(fmin(255, fmax(0,rgb[c]))));
            if (diff > *max_error) *max_error = diff;
            if (diff > 3) fprintf(stderr, "matrix=%d full=%d block=%d channel=%d got=%d reference=%.3f\n",
                                  hd,full,i,c,component(px,c),rgb[c]);
            assert(diff <= 3);
        }
    }
    for (int i = 0; i < 40; i++) assert(pixels[98*i+96] == 0x12345678 && pixels[98*i+97] == 0x12345678);
    assert(pixels[0] == 0xff000000 && pixels[39*98] == 0xff000000);
    av_frame_free(&f);
}

static void geometry(RgbaRenderer *r, int angle) {
    AVFrame *f = image(AV_PIX_FMT_RGBA, 12, 8);
    const uint32_t quadrant[] = {0xff0000ff,0xff00ff00,0xffff0000,0xffffffff};
    for (int y = 0; y < 8; y++)
        for (int x = 0; x < 12; x++) {
            uint32_t p = x < 1 || x > 8 || y < 1 || y > 6 ? 0xff00ffff : quadrant[(y > 3)*2 + (x > 4)];
            memcpy(f->data[0] + y*f->linesize[0] + 4*x, &p, 4);
        }
    f->crop_left = 1; f->crop_right = 3; f->crop_top = f->crop_bottom = 1;
    f->sample_aspect_ratio = (AVRational){3,2}; // 8x6 visible -> 2:1 before rotation.
    AVFrameSideData *sd = av_frame_new_side_data(f, AV_FRAME_DATA_DISPLAYMATRIX, 9*sizeof(int32_t));
    assert(sd);
    av_display_rotation_set((int32_t *)sd->data, angle);
    uint32_t out[67*64];
    for (unsigned i=0; i < sizeof(out)/sizeof(*out); i++) out[i] = 0x12345678;
    int ret = rgba_render(r, f, (AVRational){1,1}, 123, out, 64, 64, 67);
    if (ret < 0) fprintf(stderr, "geometry rotation=%d: %s (%d)\n", angle, av_err2str(ret), ret);
    assert(ret == 0);
    int quarter = (angle == 90 || angle == 270);
    int x0 = quarter ? 16 : 0, y0 = quarter ? 0 : 16, w = 64-2*x0, h = 64-2*y0;
    for (int y = 0; y < 64; y++) {
        for (int x = 0; x < 64; x++)
            if (x<x0 || y<y0 || x>=x0+w || y>=y0+h) assert(out[67*y+x] == 0xff000000);
        for (int x = 64; x < 67; x++) assert(out[67*y+x] == 0x12345678);
    }
    for (int i = 0; i < 4; i++) {
        int sx=i%2, sy=i/2, rx=sx, ry=sy;
        if (angle == 90) { rx=1-sy; ry=sx; }
        if (angle == 180) { rx=1-sx; ry=1-sy; }
        if (angle == 270) { rx=sy; ry=1-sx; }
        assert(out[(y0+(2*ry+1)*h/4)*67+x0+(2*rx+1)*w/4] == quadrant[i]);
    }
    av_frame_free(&f);
}

int main(void) {
    RgbaRenderer r = {0};
    int error = 0;
    for (int i=0; i<4; i++) geometry(&r,90*i);
    for (int i=0; i<2; i++)
        for (int tagged=0; tagged<2; tagged++)
            for (int full=0; full<2; full++) color(&r, i ? 608 : 40, tagged, full, &error);
    AVFrame *large = image(AV_PIX_FMT_RGBA, 4096, 2160);
    uint32_t dst[4] = {0};
    assert(rgba_render(&r, large, (AVRational){1,1}, 0, dst, 2, 2, 2) == AVERROR(ENOMEM));
    av_frame_free(&large);
    printf("PASS angles=0/90/180/270 crop=1,1,3,1 SAR=3:2 padded_source_and_destination=verified\n");
    printf("PASS SDR palettes=8 max_channel_error=%d/255 observed_peak_bytes=%zu over_budget=ENOMEM\n", error, r.peak_bytes);
    rgba_renderer_close(&r);
}
