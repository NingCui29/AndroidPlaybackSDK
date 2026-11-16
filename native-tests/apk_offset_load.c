/* Load exactly the installed-form APK's uncompressed ELF entries through Android's loader. */
#include <android/dlext.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <inttypes.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    if (argc != 3 || sysconf(_SC_PAGESIZE) != 16384) return 2;
    int fd = open(argv[1], O_RDONLY | O_CLOEXEC);
    FILE *entries = fopen(argv[2], "r");
    if (fd < 0 || !entries) return 2;
    const char *names[] = {"libavutil.so", "libswresample.so", "libswscale.so", "libavcodec.so",
                           "libavformat.so", "libplayback.so"};
    void *loaded[6];
    for (unsigned i = 0; i < 6; ++i) {
        int64_t offset;
        char name[64];
        if (fscanf(entries, "%63s %" SCNd64, name, &offset) != 2 ||
                strcmp(name, names[i]) || offset <= 0 || offset % 16384) return 2;
        android_dlextinfo source = {.flags=ANDROID_DLEXT_USE_LIBRARY_FD | ANDROID_DLEXT_USE_LIBRARY_FD_OFFSET,
                                    .library_fd=fd, .library_fd_offset=offset};
        loaded[i] = android_dlopen_ext(name, RTLD_NOW | RTLD_GLOBAL, &source);
        if (!loaded[i]) { fprintf(stderr, "load %s: %s\n", name, dlerror()); return 1; }
        FILE *maps = fopen("/proc/self/maps", "r");
        char line[1024], mapped_path[256];
        int mapped = 0;
        if (!maps) return 2;
        while (fgets(line, sizeof(line), maps)) {
            uint64_t mapped_offset;
            if (sscanf(line, "%*s %*s %" SCNx64 " %*s %*s %255s", &mapped_offset, mapped_path) == 2 &&
                    mapped_offset == (uint64_t)offset && !strcmp(mapped_path, argv[1])) mapped = 1;
        }
        fclose(maps);
        if (!mapped) { fprintf(stderr, "Missing APK-backed mapping for %s\n", name); return 1; }
        printf("APK=%s ELF=%s offset=%" PRId64 " loaded\n", argv[1], name, offset);
    }
    fclose(entries);
    close(fd);
    const char *(*version)(void) = dlsym(loaded[3], "av_version_info");
    if (!version || strcmp(version(), "8.1.3")) return 1;
    // This test-only DSO links normally against the already loaded SDK libraries.
    void *probe = dlopen("/probe/pcm.so", RTLD_NOW | RTLD_LOCAL);
    if (!probe) { fprintf(stderr, "PCM test linkage: %s\n", dlerror()); return 1; }
    int (*decode)(int, char **) = dlsym(probe, "decode_count_main");
    char *args[] = {"decode_count", "/probe/A04.wav", "384000", NULL};
    int ok = decode && !decode(3, args);
    printf("APK=%s page=%ld FFmpeg=%s PCM=%s\n", argv[1], sysconf(_SC_PAGESIZE), version(), ok ? "PASS" : "FAIL");
    return !ok;
}
