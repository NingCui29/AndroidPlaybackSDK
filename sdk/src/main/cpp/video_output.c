#include "playback_input.h"
#include "software_decoder.h"
#include <libswscale/swscale.h>
#include <libavutil/pixdesc.h>
#include <android/native_window_jni.h>
#include <poll.h>
#include <libavutil/time.h>
#include <libavutil/common.h>
#include <libavutil/display.h>
#include <math.h>

#include "output_sinks.h"

void video_stream_geometry(VideoSink *sink, const AVStream *stream) {
    sink->sar = stream->sample_aspect_ratio;
    if (sink->sar.num <= 0 || sink->sar.den <= 0) sink->sar = stream->codecpar->sample_aspect_ratio;
    const AVPacketSideData *side = av_packet_side_data_get(stream->codecpar->coded_side_data,
            stream->codecpar->nb_coded_side_data, AV_PKT_DATA_DISPLAYMATRIX);
    if (side && side->size >= 9 * sizeof(int32_t)) {
        double angle = -av_display_rotation_get((const int32_t *)side->data);
        if (isfinite(angle)) sink->rotation = (int)lround(angle);
    }
}

static void compute_buffer_geometry(int win_w, int win_h, const AVFrame *frame,
                                    AVRational sar, int rotation,
                                    int *out_w, int *out_h) {
    int vid_w = frame->width;
    int vid_h = frame->height;
    if (frame->crop_left || frame->crop_right || frame->crop_top || frame->crop_bottom) {
        int cw = vid_w - (int)frame->crop_left - (int)frame->crop_right;
        int ch = vid_h - (int)frame->crop_top - (int)frame->crop_bottom;
        if (cw > 0 && ch > 0) {
            vid_w = cw;
            vid_h = ch;
        }
    }
    if (sar.num <= 0 || sar.den <= 0) sar = frame->sample_aspect_ratio;
    if (sar.num <= 0 || sar.den <= 0) sar = (AVRational){1, 1};

    int angle = (rotation % 360 + 360) % 360;
    const AVFrameSideData *sd = av_frame_get_side_data(frame, AV_FRAME_DATA_DISPLAYMATRIX);
    if (sd && sd->size >= 9 * sizeof(int32_t)) {
        double a = -av_display_rotation_get((const int32_t *)sd->data);
        if (isfinite(a)) angle = ((int)lround(a) % 360 + 360) % 360;
    }

    double radians = angle * (3.14159265358979323846 / 180.0);
    double sine = sin(radians), cosine = cos(radians);
    if (!(angle % 90)) {
        sine = angle == 90 ? 1 : angle == 270 ? -1 : 0;
        cosine = angle == 0 ? 1 : angle == 180 ? -1 : 0;
    }
    double sw = vid_w * av_q2d(sar), sh = vid_h;
    int rw = (int)ceil(fabs(cosine) * sw + fabs(sine) * sh);
    int rh = (int)ceil(fabs(sine) * sw + fabs(cosine) * sh);
    if (rw <= 0) rw = vid_w;
    if (rh <= 0) rh = vid_h;

    int buf_w = rw;
    int buf_h = rh;

    if (win_w > 0 && win_h > 0) {
        if ((int64_t)win_w * rh >= (int64_t)win_h * rw) {
            buf_h = rh <= win_h ? rh : win_h;
            buf_w = (int)lround((double)buf_h * win_w / win_h);
        } else {
            buf_w = rw <= win_w ? rw : win_w;
            buf_h = (int)lround((double)buf_w * win_h / win_w);
        }
        if (buf_w > win_w) buf_w = win_w;
        if (buf_h > win_h) buf_h = win_h;
    }

    buf_w = (buf_w + 1) & ~1;
    buf_h = (buf_h + 1) & ~1;
    if (win_w > 0 && buf_w > win_w && (win_w & ~1) > 0) buf_w = win_w & ~1;
    if (win_h > 0 && buf_h > win_h && (win_h & ~1) > 0) buf_h = win_h & ~1;
    if (buf_w < 2) buf_w = 2;
    if (buf_h < 2) buf_h = 2;

    *out_w = buf_w;
    *out_h = buf_h;
}

int video_post(void *opaque, const AVFrame *frame) {
    VideoSink *sink = opaque;
    struct pollfd cancel = {sink->cancel_fd, POLLIN, 0};
    if (poll(&cancel, 1, 0) > 0) return AVERROR_EXIT;

    if (!sink->geometry_set) {
        sink->window_w = ANativeWindow_getWidth(sink->window);
        sink->window_h = ANativeWindow_getHeight(sink->window);
        sink->buffer_w = 0;
        sink->buffer_h = 0;
        sink->frame_w = 0;
        sink->frame_h = 0;
        sink->geometry_set = 1;
    }

    if (sink->frame_w != frame->width || sink->frame_h != frame->height ||
        sink->buffer_w <= 0 || sink->buffer_h <= 0) {
        int buf_w = 0, buf_h = 0;
        compute_buffer_geometry(sink->window_w, sink->window_h, frame,
                                sink->sar, sink->rotation, &buf_w, &buf_h);
        if (ANativeWindow_setBuffersGeometry(sink->window, buf_w, buf_h, WINDOW_FORMAT_RGBA_8888))
            return AVERROR_EXTERNAL;
        sink->buffer_w = buf_w;
        sink->buffer_h = buf_h;
        sink->frame_w = frame->width;
        sink->frame_h = frame->height;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(sink->window, &buffer, NULL)) return AVERROR_EXTERNAL;
    int ret = buffer.format == WINDOW_FORMAT_RGBA_8888 ?
            rgba_render(&sink->renderer, frame, sink->sar, sink->rotation, buffer.bits,
                        buffer.width, buffer.height, buffer.stride) : AVERROR_EXTERNAL;
    if (ANativeWindow_unlockAndPost(sink->window)) return AVERROR_EXTERNAL;
    if (ret == 0) sink->posted++;
    return ret;
}

typedef struct TimedVideo {
    VideoSink output;
    AVRational time_base;
    int64_t anchor_pts, anchor_wall, next_pts, interval;
    int anchored;
} TimedVideo;

static int present_video(void *opaque, const AVFrame *frame) {
    TimedVideo *video = opaque;
    int64_t pts = frame->best_effort_timestamp;
    if (pts != AV_NOPTS_VALUE) pts = av_rescale_q(pts, video->time_base, AV_TIME_BASE_Q);
    else if (video->anchored && video->interval > 0) pts = video->next_pts;
    else return video_post(&video->output, frame);
    int64_t now = av_gettime_relative();
    int64_t jump = av_sat_sub64(pts, video->next_pts);
    if (!video->anchored || jump > 500000 || jump < -500000) {
        video->anchor_pts = pts;
        video->anchor_wall = now;
        video->anchored = 1;
    }
    if (frame->duration > 0)
        video->interval = av_rescale_q(frame->duration, video->time_base, AV_TIME_BASE_Q);
    video->next_pts = av_sat_add64(pts, video->interval);
    int64_t target = av_sat_add64(video->anchor_wall, av_sat_sub64(pts, video->anchor_pts));
    struct pollfd cancel = {video->output.cancel_fd, POLLIN, 0};
    while (now < target) {
        int64_t remaining = av_sat_sub64(target, now);
        int status = poll(&cancel, 1, (int)((FFMIN(remaining, 100000) + 999) / 1000));
        if (status > 0) return AVERROR_EXIT;
        if (status < 0 && errno != EINTR) return AVERROR(errno);
        now = av_gettime_relative();
    }
    return video_post(&video->output, frame);
}

JNIEXPORT jint JNICALL Java_com_cn_media_playback_NativeInput_playVideo(
        JNIEnv *env, jclass type, jlong handle, jobject surface) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    int stream = av_find_best_stream(in->format, AVMEDIA_TYPE_VIDEO, -1, -1, NULL, 0);
    if (stream < 0) return stream;
    TimedVideo sink = {.output={.window=ANativeWindow_fromSurface(env, surface), .cancel_fd=in->cancel_fd},
                       .time_base=in->format->streams[stream]->time_base};
    if (!sink.output.window) return AVERROR_EXTERNAL;
    video_stream_geometry(&sink.output, in->format->streams[stream]);
    SoftwareDecoder decoder = {0};
    AVPacket *pkt = av_packet_alloc();
    int ret = pkt ? software_decoder_open(&decoder, in->format->streams[stream]) : AVERROR(ENOMEM);
    while (ret >= 0) {
        ret = av_read_frame(in->format, pkt);
        if (ret == AVERROR_EOF) {
            ret = software_decoder_submit(&decoder, NULL, present_video, &sink);
            break;
        }
        if (ret < 0) break;
        if (pkt->stream_index == stream) ret = software_decoder_submit(&decoder, pkt, present_video, &sink);
        av_packet_unref(pkt);
    }
    av_packet_free(&pkt);
    software_decoder_close(&decoder);
    rgba_renderer_close(&sink.output.renderer);
    ANativeWindow_release(sink.output.window);
    return ret < 0 ? ret : sink.output.posted;
}
