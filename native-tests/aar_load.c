/* Verify the release JNI's immediate relocations and its linked FFmpeg version on Android. */
#include <dlfcn.h>
#include <stdio.h>
#include <string.h>
#include <jni.h>

int main(void) {
    void *sdk = dlopen("libplayback.so", RTLD_NOW | RTLD_LOCAL);
    if (!sdk) { fprintf(stderr, "%s\n", dlerror()); return 1; }
    const char *(*version)(void) = (const char *(*)(void))dlsym(sdk, "av_version_info");
    jint (*fds)(JNIEnv *, jclass) = (jint (*)(JNIEnv *, jclass))
            dlsym(sdk, "Java_com_cn_media_playback_NativeInput_liveInputFds");
    /* This counter reads a C atomic and takes no Java references. */
    int ok = version && fds && !strcmp(version(), "8.1.3") && fds(NULL, NULL) == 0;
    printf("%s dlopen=RTLD_NOW ffmpeg=%s jniLiveInputFds=%d\n", ok ? "PASS" : "FAIL",
            version ? version() : "missing", fds ? fds(NULL, NULL) : -1);
    dlclose(sdk);
    return !ok;
}
