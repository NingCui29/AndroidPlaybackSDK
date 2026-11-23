#include "output_sinks.h"
#include "audio_track_probe.h"
#include "recovery_gop.h"
#include <libavutil/pixdesc.h>
#include <libavutil/time.h>
#include <libavutil/common.h>
#include <pthread.h>
#include <sys/eventfd.h>

// One epoch, shared until playback_decode_tracks has joined both selected workers.
typedef struct RenderSession {
    PlaybackInput *input;
    JavaVM *vm;
    pthread_key_t attachment;
    jobject output;
    jmethodID begin_audio, end_audio, acquire_video, finish_video, end_video, request_stop;
    AudioSink audio;
    VideoSink video;
    jweak last_cpu_surface;
    AVFrame *late_frame;
    jmethodID late_video;
    jmethodID software_info;
    int reported_color;
    size_t reported_peak;
    AVRational audio_time, video_time;
    int64_t origin, target, next_audio;
    int64_t audio_floor;
    int audio_index;
    int audio_started;
    SoftwareDecoder selected_audio;
    jmethodID selected_index, selected_floor, selection_version, switching_audio_failed;
    int64_t audio_version;
    jmethodID hardware_packet, software_fallback, position_ms, track_unavailable;
    const AVStream *video_stream;
    RecoveryGop gop;
    SoftwareDecoder fallback;
    int64_t recovery_floor, wait_key_until;
    int video_lost, has_hardware, extra_size;
    uint8_t *current_extra;
    jmethodID flush_picture;
    jmethodID configuration_wait;
} RenderSession;

static void detach_worker(void *opaque) {
    JavaVM *vm = opaque;
    (*vm)->DetachCurrentThread(vm);
}

static JNIEnv *worker_env(RenderSession *s) {
    JNIEnv *env = NULL;
    if ((*s->vm)->GetEnv(s->vm, (void **)&env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*s->vm)->AttachCurrentThread(s->vm, &env, NULL) != JNI_OK) return NULL;
    if (pthread_setspecific(s->attachment, s->vm)) {
        detach_worker(s->vm);
        return NULL;
    }
    return env;
}

static int checked(JNIEnv *env, int value) {
    if (!(*env)->ExceptionCheck(env)) return value;
    (*env)->ExceptionClear(env);
    return AVERROR_EXTERNAL;
}

static void interrupt_output(void *opaque) {
    RenderSession *s = opaque;
    while (eventfd_write(s->input->cancel_fd, 1) < 0 && errno == EINTR) {}
    JNIEnv *env = worker_env(s);
    if (env) {
        // Cancellation may run under group_lock. Frame ownership is returned by the render worker.
        (*env)->CallVoidMethod(env, s->output, s->request_stop);
        checked(env, 0);
    }
}

static int64_t frame_us(const AVFrame *frame, AVRational time, int64_t origin) {
    if (frame->best_effort_timestamp == AV_NOPTS_VALUE) return AV_NOPTS_VALUE;
    return av_sat_sub64(av_rescale_q(frame->best_effort_timestamp, time, AV_TIME_BASE_Q), origin);
}

static int audio_frame(void *opaque, const AVFrame *frame) {
    RenderSession *s = opaque;
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    s->audio.env = env;
    int64_t pts = frame_us(frame, s->audio_time, s->origin);
    if (pts == AV_NOPTS_VALUE) pts = s->next_audio;
    if (frame->sample_rate <= 0) return AVERROR_INVALIDDATA;
    int64_t deviation = av_sat_sub64(pts, s->next_audio);
    int jump = s->audio_started && (deviation < -500000 || deviation > 500000);
    s->next_audio = av_sat_add64(pts, av_rescale(frame->nb_samples, 1000000, frame->sample_rate));
    int64_t skip = 0;
    if (!s->audio_started && pts < s->audio_floor) {
        skip = av_rescale_rnd(av_sat_sub64(s->audio_floor, pts), frame->sample_rate, 1000000, AV_ROUND_UP);
        if (skip >= frame->nb_samples) return 0;
        pts = av_sat_add64(pts, av_rescale(skip, 1000000, frame->sample_rate));
    }
    if (jump && s->audio.swr) {
        // Flush delayed old-segment samples before recording the new segment's PCM boundary.
        int tail = checked(env, audio_flush(&s->audio));
        if (tail < 0) return tail;
        swr_free(&s->audio.swr);
        av_channel_layout_uninit(&s->audio.layout);
    }
    int ret = checked(env, (*env)->CallIntMethod(env, s->output, s->begin_audio, s->audio_index,
                                                (jlong)pts, (jboolean)jump));
    if (ret < 0) return AVERROR_EXIT;
    if (ret > 0) return 0;
    s->audio_started = 1;
    return checked(env, audio_deliver_from(&s->audio, frame, (int)skip));
}

// Audio is switched in its own worker. The video decoder, demux position and
// video queue stay live, including when the input cannot seek backwards.
static int selected_audio_packet(void *opaque, const AVPacket *packet) {
    RenderSession *s = opaque;
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    int selected = (*env)->CallIntMethod(env, s->output, s->selected_index);
    if (checked(env, 0) < 0) return AVERROR_EXTERNAL;
    int64_t version = (*env)->CallLongMethod(env, s->output, s->selection_version);
    if (selected != s->audio_index || version != s->audio_version) {
        software_decoder_close(&s->selected_audio);
        swr_free(&s->audio.swr);
        av_channel_layout_uninit(&s->audio.layout);
        s->audio_index = selected;
        s->audio_started = 0;
        s->audio_version = version;
        s->audio_floor = s->next_audio = (*env)->CallLongMethod(env, s->output, s->selected_floor);
    }
    if (selected < 0 || (packet && packet->stream_index != selected)) return 0;
    if (!packet && !s->selected_audio.codec) return 0;
    int ret = 0;
    if (!s->selected_audio.codec) {
        AVStream *stream = s->input->format->streams[selected];
        s->audio_time = stream->time_base;
        ret = software_decoder_open(&s->selected_audio, stream);
    }
    if (ret >= 0) ret = software_decoder_submit(&s->selected_audio, packet, audio_frame, s);
    if (ret < 0 && ret != AVERROR_EXIT) {
        int handled = checked(env, (*env)->CallIntMethod(env, s->output, s->switching_audio_failed, selected, ret));
        if (handled == 1) return 0;
    }
    return ret;
}

static int drain_audio(void *opaque) {
    RenderSession *s = opaque;
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    int ret = checked(env, audio_flush(&s->audio));
    if (ret >= 0) ret = checked(env, (*env)->CallIntMethod(env, s->output, s->end_audio));
    return ret < 0 ? AVERROR_EXTERNAL : 0;
}

static int submit_video(void *opaque, const AVFrame *frame) {
    RenderSession *s = opaque;
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    int64_t pts = frame_us(frame, s->video_time, s->origin);
    int64_t duration = frame->duration > 0 ? av_rescale_q(frame->duration, s->video_time, AV_TIME_BASE_Q) : 0;
    if (s->recovery_floor > 0 && pts != AV_NOPTS_VALUE &&
        av_sat_add64(pts, duration) <= s->recovery_floor) return 0;
    if (pts != AV_NOPTS_VALUE) s->recovery_floor = 0;
    jobject surface = (*env)->CallObjectMethod(env, s->output, s->acquire_video, (jlong)pts, (jlong)duration);
    if (!surface) {
        if (checked(env, 0) < 0) return AVERROR_EXTERNAL;
        return s->input->format->interrupt_callback.callback(s->input) ? AVERROR_EXIT : 0;
    }
    if (!s->last_cpu_surface || !(*env)->IsSameObject(env, surface, s->last_cpu_surface)) {
        if (s->last_cpu_surface) (*env)->DeleteWeakGlobalRef(env, s->last_cpu_surface);
        s->last_cpu_surface = (*env)->NewWeakGlobalRef(env, surface);
        s->video.geometry_set = 0;
    }
    s->video.window = !(*env)->ExceptionCheck(env) ? ANativeWindow_fromSurface(env, surface) : NULL;
    int ret = AVERROR_EXTERNAL;
    if (s->video.window) {
        ret = video_post(&s->video, frame);
        ANativeWindow_release(s->video.window);
        s->video.window = NULL;
    }
    (*env)->DeleteLocalRef(env, surface);
    ret = checked(env, ret);
    (*env)->CallVoidMethod(env, s->output, s->finish_video, (jlong)pts, (jboolean)(ret >= 0));
    if (ret >= 0 && !(*env)->ExceptionCheck(env) && (s->video.renderer.color_policy != s->reported_color ||
        s->video.renderer.peak_bytes != s->reported_peak)) {
        s->reported_color = s->video.renderer.color_policy;
        s->reported_peak = s->video.renderer.peak_bytes;
        (*env)->CallVoidMethod(env, s->output, s->software_info, (jint)s->reported_color, (jlong)s->reported_peak);
    }
    return checked(env, ret);
}

static int finish_pending_video(void *opaque) {
    RenderSession *s = opaque;
    if (!s->late_frame) return 0;
    int ret = submit_video(s, s->late_frame);
    av_frame_free(&s->late_frame);
    return ret;
}

static int late_video(RenderSession *s, JNIEnv *env, const AVFrame *frame, int64_t newer) {
    int64_t pts = frame_us(frame, s->video_time, s->origin);
    int64_t duration = frame->duration > 0 ? av_rescale_q(frame->duration, s->video_time, AV_TIME_BASE_Q) : 0;
    return checked(env, (*env)->CallIntMethod(env, s->output, s->late_video, (jlong)pts,
                    (jlong)duration, (jlong)newer));
}

static int video_frame(void *opaque, const AVFrame *frame) {
    RenderSession *s = opaque;
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    if (s->late_frame) {
        int64_t pts = frame_us(frame, s->video_time, s->origin);
        int discard = pts != AV_NOPTS_VALUE ? late_video(s, env, s->late_frame, pts) : 0;
        if (discard < 0) return discard;
        if (discard) av_frame_free(&s->late_frame);
        else {
            int ret = finish_pending_video(s);
            if (ret < 0) return ret;
        }
    }
    // Hold at most one already-late display frame until the decoder emits a
    // genuinely newer frame. Never wait on the network/pipe for a replacement.
    size_t bytes = rgba_frame_bytes(frame);
    if (bytes <= VIDEO_FRAME_BUDGET / 2 &&
        bytes <= VIDEO_FRAME_BUDGET - s->video.renderer.scratch_size) {
        int late = late_video(s, env, frame, AV_NOPTS_VALUE);
        if (late < 0) return late;
        if (late) {
            s->late_frame = av_frame_clone(frame);
            return s->late_frame ? 0 : AVERROR(ENOMEM);
        }
    }
    return submit_video(s, frame);
}

static int drain_video(void *opaque) {
    RenderSession *s = opaque;
    if (s->video_lost) return 0;
    int ret = finish_pending_video(s);
    if (ret < 0) return ret;
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    return checked(env, (*env)->CallIntMethod(env, s->output, s->end_video)) < 0 ? AVERROR_EXTERNAL : 0;
}

static int track_unavailable(RenderSession *s, JNIEnv *env, int video, int reason) {
    jint outcome = checked(env, (*env)->CallIntMethod(env, s->output, s->track_unavailable,
            (jboolean)video, (jint)reason));
    if (outcome == 1) return 0;
    return outcome == 2 ? AVERROR_STREAM_NOT_FOUND : reason;
}

static int lose_video(RenderSession *s, JNIEnv *env, int reason) {
    int ret = track_unavailable(s, env, 1, reason);
    if (ret < 0) return ret;
    s->video_lost = 1;
    recovery_gop_clear(&s->gop);
    return 0;
}

static int failed_audio(void *opaque, int reason) {
    RenderSession *s = opaque;
    JNIEnv *env = worker_env(s);
    return env ? track_unavailable(s, env, 0, reason) : reason;
}

static int failed_video(void *opaque, int reason) {
    RenderSession *s = opaque;
    // Hardware failures pass through the per-policy recovery path before degradation.
    if (reason == PLAYBACK_HW_RUNTIME || reason == PLAYBACK_HW_INIT ||
            reason == AVERROR_STREAM_NOT_FOUND) return reason;
    JNIEnv *env = worker_env(s);
    return env ? lose_video(s, env, reason) : reason;
}

static int recover_hardware(RenderSession *s, JNIEnv *env, int status, const AVPacket *packet, int draining) {
    if (!status) return 0;
    if (status == -3) return AVERROR_EXIT;
    if (status == -4) return AVERROR(ENOMEM);
    if (status == -5) return AVERROR_INVALIDDATA;
    if (status == -1) return PLAYBACK_HW_INIT;
    if (status == -2 || (status == 2 && s->input->seekable)) return PLAYBACK_HW_RUNTIME;
    int ret = software_decoder_open_config(&s->fallback, s->video_stream, s->current_extra, s->extra_size);
    if (ret < 0) return lose_video(s, env, ret);
    (*env)->CallVoidMethod(env, s->output, s->software_fallback);
    if (status == 2) s->recovery_floor = (*env)->CallLongMethod(env, s->output, s->position_ms) * 1000;
    if (checked(env, 0) < 0) return AVERROR_EXTERNAL;
    if (s->gop.count) {
        for (unsigned i = 0; ret >= 0 && i < s->gop.count; ++i)
            ret = software_decoder_submit(&s->fallback, s->gop.packets[i], video_frame, s);
        recovery_gop_clear(&s->gop);
        if (draining && ret >= 0) ret = software_decoder_submit(&s->fallback, NULL, video_frame, s);
    } else if (packet && ((packet->flags & AV_PKT_FLAG_KEY) || status == 1)) {
        ret = software_decoder_submit(&s->fallback, packet, video_frame, s);
    } else if (!draining) s->wait_key_until = av_gettime_relative() + 2000000;
    else return lose_video(s, env, PLAYBACK_HW_RUNTIME);
    return ret;
}

static int video_before_wait(void *opaque) {
    RenderSession *s = opaque;
    if (s->wait_key_until && av_gettime_relative() >= s->wait_key_until && !s->video_lost) {
        JNIEnv *env = worker_env(s);
        return env ? lose_video(s, env, PLAYBACK_HW_RUNTIME) : AVERROR_EXTERNAL;
    }
    if (!s->has_hardware || s->fallback.codec || s->video_lost) return finish_pending_video(s);
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    int status = (*env)->CallIntMethod(env, s->output, s->flush_picture);
    if (checked(env, 0) < 0) return AVERROR_EXTERNAL;
    return recover_hardware(s, env, status, NULL, 0);
}

static int64_t video_wait_us(void *opaque) {
    RenderSession *s = opaque;
    if (s->video_lost) return -1;
    if (s->wait_key_until) return FFMAX(0, s->wait_key_until - av_gettime_relative());
    if (s->has_hardware && !s->fallback.codec) {
        JNIEnv *env = worker_env(s);
        if (env) {
            int ms = checked(env, (*env)->CallIntMethod(env, s->output, s->configuration_wait));
            if (ms >= 0) return (int64_t)ms * 1000;
        }
    }
    return -1;
}

static int hardware_packet(void *opaque, const AVPacket *packet) {
    RenderSession *s = opaque;
    if (s->video_lost) return 0;
    JNIEnv *env = worker_env(s);
    if (!env) return AVERROR_EXTERNAL;
    if (s->fallback.codec && !s->wait_key_until)
        return software_decoder_submit(&s->fallback, packet, video_frame, s);
    if (s->wait_key_until) {
        if (!packet || av_gettime_relative() >= s->wait_key_until) return lose_video(s, env, PLAYBACK_HW_RUNTIME);
        if (!(packet->flags & AV_PKT_FLAG_KEY)) return 0;
        s->wait_key_until = 0;
        return software_decoder_submit(&s->fallback, packet, video_frame, s);
    }
    jobject buffer = NULL, config = NULL;
    int64_t pts = AV_NOPTS_VALUE, duration = 0;
    if (packet) {
        if (!packet->size) return 0;
        size_t extra_size = 0;
        const uint8_t *extra = av_packet_get_side_data(packet, AV_PKT_DATA_NEW_EXTRADATA, &extra_size);
        if (extra && extra_size) {
            if (extra_size > 4 * 1024 * 1024) return AVERROR(ENOBUFS);
            uint8_t *copy = av_memdup(extra, extra_size);
            if (!copy) return AVERROR(ENOMEM);
            av_freep(&s->current_extra);
            s->current_extra = copy; s->extra_size = (int)extra_size;
            recovery_gop_clear(&s->gop);
            config = (*env)->NewDirectByteBuffer(env, (void *)extra, extra_size);
            if (!config) return AVERROR(ENOMEM);
        }
        if (!s->input->seekable) recovery_gop_remember(&s->gop, packet, s->video_time);
        int64_t timestamp = packet->pts;
        if (timestamp == AV_NOPTS_VALUE && !s->video_stream->codecpar->video_delay) timestamp = packet->dts;
        if (timestamp != AV_NOPTS_VALUE) pts = av_sat_sub64(av_rescale_q(timestamp, s->video_time, AV_TIME_BASE_Q), s->origin);
        duration = av_rescale_q(packet->duration, s->video_time, AV_TIME_BASE_Q);
        buffer = (*env)->NewDirectByteBuffer(env, packet->data, packet->size);
        if (!buffer) return AVERROR(ENOMEM);
    }
    int status = (*env)->CallIntMethod(env, s->output, s->hardware_packet, buffer, (jlong)pts,
            (jlong)duration, (jboolean)(packet && (packet->flags & AV_PKT_FLAG_KEY)), config);
    if (config) (*env)->DeleteLocalRef(env, config);
    if (buffer) (*env)->DeleteLocalRef(env, buffer);
    if (checked(env, 0) < 0) return AVERROR_EXTERNAL;
    return recover_hardware(s, env, status, packet, !packet);
}

JNIEXPORT jint JNICALL Java_com_cn_media_playback_NativeInput_render(JNIEnv *env, jclass cls,
        jlong handle, jint audio, jint video, jlong target_ms, jobject output) {
    PlaybackInput *in = (PlaybackInput *)(intptr_t)handle;
    if (!in->format || (audio < 0 && video < 0) ||
        audio >= (int)in->format->nb_streams || video >= (int)in->format->nb_streams)
        return AVERROR_STREAM_NOT_FOUND;
    RenderSession s = {.input=in, .target=target_ms * 1000, .next_audio=target_ms * 1000,
            .audio_floor=target_ms * 1000, .audio_index=audio};
    s.origin = in->format->start_time == AV_NOPTS_VALUE ? 0 : in->format->start_time;
    s.video.cancel_fd = in->cancel_fd;
    if ((*env)->GetJavaVM(env, &s.vm) != JNI_OK) return AVERROR_EXTERNAL;
    jclass output_type = (*env)->GetObjectClass(env, output);
    if (!output_type) return AVERROR_EXTERNAL;
#define METHOD(field, name, sig) do { s.field = (*env)->GetMethodID(env, output_type, name, sig); \
    if (!s.field) return AVERROR_EXTERNAL; } while (0)
    METHOD(audio.configure, "configure", "(II)I");
    METHOD(audio.write, "write", "(Ljava/nio/ByteBuffer;)I");
    METHOD(begin_audio, "beginAudio", "(IJZ)I");
    METHOD(selected_index, "selectedAudioIndex", "()I");
    METHOD(selected_floor, "selectedAudioFloorUs", "()J");
    METHOD(selection_version, "audioSelectionVersion", "()J");
    METHOD(switching_audio_failed, "switchingAudioFailed", "(II)I");
    METHOD(end_audio, "endAudio", "()I");
    METHOD(acquire_video, "acquireVideo", "(JJ)Landroid/view/Surface;");
    METHOD(late_video, "lateVideo", "(JJJ)I");
    METHOD(software_info, "softwareVideoInfo", "(IJ)V");
    METHOD(finish_video, "finishVideo", "(JZ)V");
    METHOD(end_video, "endVideo", "()I");
    METHOD(request_stop, "requestStop", "()V");
    METHOD(hardware_packet, "hardwarePacket", "(Ljava/nio/ByteBuffer;JJZLjava/nio/ByteBuffer;)I");
    METHOD(flush_picture, "flushHardwarePicture", "()I");
    METHOD(configuration_wait, "configurationWaitMs", "()I");
    METHOD(software_fallback, "softwareFallback", "()V");
    METHOD(position_ms, "positionMs", "()J");
    METHOD(track_unavailable, "trackUnavailable", "(ZI)I");
#undef METHOD
    int err = pthread_key_create(&s.attachment, detach_worker);
    if (err) return AVERROR(err);
    s.output = (*env)->NewGlobalRef(env, output);
    s.audio.output = s.output;
    int ret = AVERROR(ENOMEM);
    if (s.output) {
        DecodeTarget targets[2];
        int count = 0;
        if (audio >= 0) {
            s.audio_time = in->format->streams[audio]->time_base;
            targets[count++] = (DecodeTarget){audio, audio_frame, &s, drain_audio, interrupt_output};
            targets[count-1].failure = failed_audio;
            if (in->audio_history) {
                targets[count-1].packet = selected_audio_packet;
                targets[count-1].observe_audio = audio_history_record;
            }
        }
        if (video >= 0) {
            s.video_stream = in->format->streams[video];
            video_stream_geometry(&s.video, s.video_stream);
            s.video_time = s.video_stream->time_base;
            targets[count++] = (DecodeTarget){video, video_frame, &s, drain_video, interrupt_output};
            targets[count-1].failure = failed_video;
            targets[count-1].before_wait = video_before_wait;
            targets[count-1].idle_wait_us = video_wait_us;
            const AVCodecParameters *p = s.video_stream->codecpar;
            jmethodID init = (*env)->GetMethodID(env, output_type, "initHardware", "(IIIFLjava/nio/ByteBuffer;)I");
            if (!init) { ret = AVERROR_EXTERNAL; goto done; }
            if (p->extradata_size > 4 * 1024 * 1024) { ret = AVERROR(ENOBUFS); goto done; }
            jobject extra = p->extradata_size > 0 ?
                    (*env)->NewDirectByteBuffer(env, p->extradata, p->extradata_size) : NULL;
            if (p->extradata_size && !extra) { ret = AVERROR(ENOMEM); goto done; }
            const AVPixFmtDescriptor *pix = av_pix_fmt_desc_get(p->format);
            AVRational fps = av_guess_frame_rate(in->format, in->format->streams[video], NULL);
            int mode = (*env)->CallIntMethod(env, output, init, p->profile, p->level, pix ? pix->comp[0].depth : 0,
                    fps.den ? (jfloat)av_q2d(fps) : 0.0f, extra);
            if (extra) (*env)->DeleteLocalRef(env, extra);
            if (checked(env, 0) < 0 || mode < 0) {
                ret = mode == -3 ? AVERROR_EXIT : mode == -4 ? AVERROR(ENOMEM) :
                        mode == -5 ? AVERROR_INVALIDDATA : PLAYBACK_HW_INIT;
                goto done;
            }
            if (mode == 1) { s.has_hardware = 1; targets[count-1].packet = hardware_packet; }
            else if (s.target > 0 && p->codec_id == AV_CODEC_ID_H264 &&
                    !strcmp(in->format->iformat->name, "mov,mp4,m4a,3gp,3g2,mj2")) {
                targets[count-1].avc_preroll_until = av_rescale_q_rnd(av_sat_add64(s.origin, s.target),
                        AV_TIME_BASE_Q, s.video_time, AV_ROUND_DOWN);
            }
        }
        ret = playback_decode_tracks(in, targets, count);
done:
        if (s.last_cpu_surface) (*env)->DeleteWeakGlobalRef(env, s.last_cpu_surface);
        (*env)->DeleteGlobalRef(env, s.output);
    }
    software_decoder_close(&s.fallback);
    software_decoder_close(&s.selected_audio);
    av_freep(&s.current_extra);
    av_frame_free(&s.late_frame);
    recovery_gop_clear(&s.gop);
    rgba_renderer_close(&s.video.renderer);
    swr_free(&s.audio.swr);
    av_channel_layout_uninit(&s.audio.layout);
    pthread_key_delete(s.attachment);
    return ret;
}
