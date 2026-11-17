#include "software_decoder.h"
#include <libavutil/cpu.h>

void software_decoder_close(SoftwareDecoder *decoder) {
    av_frame_free(&decoder->frame);
    avcodec_free_context(&decoder->codec);
}

int software_decoder_open(SoftwareDecoder *decoder, const AVStream *stream) {
    return software_decoder_open_config(decoder, stream, NULL, 0);
}

int software_decoder_open_config(SoftwareDecoder *decoder, const AVStream *stream,
                                 const uint8_t *extra, int extra_size) {
    const AVCodec *codec = avcodec_find_decoder(stream->codecpar->codec_id);
    if (!codec) return AVERROR_DECODER_NOT_FOUND;
    decoder->codec = avcodec_alloc_context3(codec);
    decoder->frame = av_frame_alloc();
    int ret = AVERROR(ENOMEM);
    if (decoder->codec && decoder->frame) {
        ret = avcodec_parameters_to_context(decoder->codec, stream->codecpar);
        if (ret >= 0 && extra) {
            av_freep(&decoder->codec->extradata);
            decoder->codec->extradata_size = 0;
            decoder->codec->extradata = av_mallocz((size_t)extra_size + AV_INPUT_BUFFER_PADDING_SIZE);
            if (!decoder->codec->extradata) ret = AVERROR(ENOMEM);
            else {
                memcpy(decoder->codec->extradata, extra, extra_size);
                decoder->codec->extradata_size = extra_size;
            }
        }
        if (ret >= 0) {
            decoder->codec->pkt_timebase = stream->time_base;
            if (stream->codecpar->codec_type == AVMEDIA_TYPE_VIDEO) {
                decoder->codec->apply_cropping = 0;
                decoder->codec->thread_type = FF_THREAD_FRAME | FF_THREAD_SLICE;
                decoder->codec->thread_count = av_clip(av_cpu_count(), 1, 6);
            }
            ret = avcodec_open2(decoder->codec, codec, NULL);
        }
    }
    if (ret < 0) software_decoder_close(decoder);
    return ret;
}

// Every submission drains available frames before another packet is sent.
// A NULL packet at demux EOF drains delay/reordering frames to decoder EOF.
int software_decoder_submit(SoftwareDecoder *decoder, const AVPacket *packet,
                            PlaybackFrameSink sink, void *opaque) {
    if (decoder->avc_preroll_until > 0) {
        // An AVC sample in MP4 contains one access unit. Keep every reference
        // picture and every sample that could cover the requested display time.
        // FFmpeg applies this setting per packet, including with frame threads.
        int64_t target = decoder->avc_preroll_until;
        if (!packet || packet->pts == AV_NOPTS_VALUE || packet->duration <= 0 ||
                packet->pts >= target || av_sat_add64(packet->pts, packet->duration) >= target) {
            decoder->avc_preroll_until = 0;
            decoder->codec->skip_frame = AVDISCARD_DEFAULT;
        } else {
            decoder->codec->skip_frame = AVDISCARD_NONREF;
        }
    }
    int ret = avcodec_send_packet(decoder->codec, packet);
    if (ret < 0) return ret;
    for (;;) {
        ret = avcodec_receive_frame(decoder->codec, decoder->frame);
        if (ret == AVERROR(EAGAIN) && packet) return 0;
        if (ret == AVERROR_EOF && !packet) return 0;
        if (ret < 0) return ret;
        ret = sink(opaque, decoder->frame);
        av_frame_unref(decoder->frame);
        if (ret < 0) return ret;
    }
}
