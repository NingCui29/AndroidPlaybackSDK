/* Capture the PCM ByteBuffers emitted by the release audio sink, before AudioTrack. */
#include "output_sinks.h"
#include <fcntl.h>
#include <libavutil/sha.h>
#include <math.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int16_t *pcm;
static size_t count, capacity, block_bytes;
static int rate, channels, configured;
static jmethodID config_id, write_id;

static jobject buffer(JNIEnv *env, void *data, jlong size) {
    block_bytes = size;
    return (jobject)data;
}
static void forget(JNIEnv *env, jobject ref) {}
static jboolean exception(JNIEnv *env) { return JNI_FALSE; }
static jint callback(JNIEnv *env, jobject obj, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    int result = 0;
    if (method == config_id) {
        int input_rate = va_arg(args, int);
        channels = va_arg(args, int);
        ++configured;
        result = rate ? rate : input_rate;
    } else if (method == write_id) {
        jobject data = va_arg(args, jobject);
        size_t n = block_bytes/2;
        if (!channels || block_bytes % (2*channels) || n > capacity-count) result = -1;
        else { memcpy(pcm+count, data, block_bytes); count += n; }
    } else result = -1;
    va_end(args);
    return result;
}
static const struct JNINativeInterface table = {.CallIntMethod=callback, .NewDirectByteBuffer=buffer,
        .DeleteLocalRef=forget, .ExceptionCheck=exception};
static JNIEnv env = &table;

static int decode(const char *name, AudioSink *sink) {
    struct stat st;
    PlaybackInput in = {.fd=open(name,O_RDONLY), .cancel_fd=-1, .seekable=1};
    if (in.fd<0 || fstat(in.fd,&st)) return -1;
    in.length=st.st_size;
    int ret=playback_probe(&in);
    if (ret>=0 && in.format->nb_streams==1) {
        DecodeTarget target = {.stream=0,.sink=audio_deliver,.opaque=sink};
        ret=playback_decode_tracks(&in,&target,1);
        if (!ret) ret=audio_flush(sink);
    }
    playback_close(&in);
    close(in.fd);
    return ret;
}

static double tone(size_t first, size_t samples, double frequency) {
    double re=0, im=0;
    for (size_t i=0;i<samples;++i) {
        double phase=2*M_PI*frequency*i/48000.;
        re+=pcm[first+i]*cos(phase); im+=pcm[first+i]*sin(phase);
    }
    return 2*hypot(re,im)/samples;
}

static void reset(AudioSink *sink) {
    swr_free(&sink->swr);
    av_channel_layout_uninit(&sink->layout);
    *sink=(AudioSink){.env=&env,.configure=config_id,.write=write_id};
    count=0; channels=0; configured=0;
}
#define CHECK(x) do { if (!(x)) {fprintf(stderr,"PCM check failed at line %d\n",__LINE__);return 1;} } while(0)

int main(int argc,char **argv) {
    if(argc!=4) return 2;
    config_id=(jmethodID)(uintptr_t)1; write_id=(jmethodID)(uintptr_t)2;
    capacity=800000; pcm=calloc(capacity,sizeof(*pcm)); CHECK(pcm);
    AudioSink sink={0};
    reset(&sink);
    CHECK(decode(argv[1],&sink)==0 && configured==1 && channels==2 && count==768000);
    struct AVSHA *hash=av_sha_alloc(); uint8_t binary[32]; char hex[65];
    CHECK(hash && av_sha_init(hash,256)==0);
    av_sha_update(hash,(unsigned char *)pcm,count*2); av_sha_final(hash,binary); av_free(hash);
    for(int i=0;i<32;i++) snprintf(hex+2*i,3,"%02x",binary[i]);
    CHECK(!strcmp(hex,argv[3]));
    printf("A04 channels=2 frames=%zu PCM-SHA256=%s\n",count/2,hex);
    reset(&sink);
    CHECK(decode(argv[2],&sink)==0 && configured==1 && channels==1 && count>=576000 && count<=577024);
    size_t starts[]={115200,288000,460800,571200}; int hz[]={440,660,880,1000};
    for(int i=0;i<4;i++) {
        // Use the central 20 ms of each cue and the last 20 ms of the tail.
        size_t at=i==3?575040:starts[i]+480;
        double wanted=tone(at,960,hz[i]),other=tone(at,960,hz[i]+500);
        CHECK(wanted>500 && wanted>10*other);
        printf("A02 atSample=%zu frequency=%d amplitude=%.1f offBand=%.1f\n",at,hz[i],wanted,other);
    }
    reset(&sink);
    AVFrame *f=av_frame_alloc(); CHECK(f);
    f->format=AV_SAMPLE_FMT_S16; f->sample_rate=48000; f->nb_samples=12;
    f->ch_layout=(AVChannelLayout)AV_CHANNEL_LAYOUT_5POINT1;
    CHECK(av_frame_get_buffer(f,0)==0);
    int16_t *src=(int16_t *)f->data[0]; memset(src,0,12*6*2);
    for(int i=0;i<6;i++)src[(i+2)*6+i]=24000;
    CHECK(audio_deliver_from(&sink,f,2)>=0 && audio_flush(&sink)==0 && channels==2 && count==20);
    double base=pcm[0];
    CHECK(base>0 && pcm[1]==0 && pcm[2]==0 && pcm[3]==base);
    CHECK(fabs(pcm[4]/base-M_SQRT1_2)<0.002 && pcm[4]==pcm[5]);
    CHECK(pcm[6]==0 && pcm[7]==0);
    CHECK(abs(pcm[8]-pcm[4])<=1 && pcm[9]==0 && pcm[10]==0 && abs(pcm[11]-pcm[5])<=1);
    CHECK(base+pcm[4]+pcm[8]<=24002);
    printf("5.1 FL=%.0f center=%d LFE=%d sideL=%d skip=2 normalized\n",base,pcm[4],pcm[6],pcm[8]);
    reset(&sink);
    av_frame_unref(f); f->format=AV_SAMPLE_FMT_FLTP; f->sample_rate=32000; f->nb_samples=3200;
    f->ch_layout=(AVChannelLayout)AV_CHANNEL_LAYOUT_MONO;
    CHECK(av_frame_get_buffer(f,0)==0); memset(f->data[0],0,3200*sizeof(float)); rate=48000;
    CHECK(audio_deliver(&sink,f)>=0 && audio_flush(&sink)==0 && channels==1 && count==4800);
    printf("32 kHz planar to 48 kHz interleaved frames=%zu including resampler drain\n",count);
    reset(&sink); f->ch_layout=(AVChannelLayout){.order=AV_CHANNEL_ORDER_UNSPEC,.nb_channels=6};
    CHECK(audio_deliver(&sink,f)==AVERROR_INVALIDDATA && !configured);
    av_frame_free(&f); free(pcm);
    puts("S01 digital PCM PASS");
    return 0;
}
