// Windows-only producer of the B02 WMV3/WVC1 video fixtures. Pass a Windows output path.
#define COBJMACROS
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <mfapi.h>
#include <mfidl.h>
#include <mfreadwrite.h>
#include <stdio.h>
#include <string.h>

enum { WIDTH = 320, HEIGHT = 240, FPS = 25 };
#define CHECK(call) do { hr = (call); if (FAILED(hr)) { \
    fprintf(stderr, "%s: HRESULT %08lx\n", #call, (unsigned long)hr); goto done; } } while (0)

static HRESULT video_type(IMFMediaType **type, const GUID *subtype) {
    HRESULT hr;
    CHECK(MFCreateMediaType(type));
    CHECK(IMFMediaType_SetGUID(*type, &MF_MT_MAJOR_TYPE, &MFMediaType_Video));
    CHECK(IMFMediaType_SetGUID(*type, &MF_MT_SUBTYPE, subtype));
    CHECK(IMFMediaType_SetUINT64(*type, &MF_MT_FRAME_SIZE, ((UINT64)WIDTH << 32) | HEIGHT));
    CHECK(IMFMediaType_SetUINT64(*type, &MF_MT_FRAME_RATE, ((UINT64)FPS << 32) | 1));
    CHECK(IMFMediaType_SetUINT64(*type, &MF_MT_PIXEL_ASPECT_RATIO, ((UINT64)1 << 32) | 1));
    CHECK(IMFMediaType_SetUINT32(*type, &MF_MT_INTERLACE_MODE, MFVideoInterlace_Progressive));
done:
    return hr;
}

static HRESULT frame(IMFSinkWriter *writer, DWORD stream, int number) {
    IMFMediaBuffer *buffer = NULL;
    IMFSample *sample = NULL;
    BYTE *bits;
    DWORD size = WIDTH * HEIGHT * 4;
    HRESULT hr;
    CHECK(MFCreateMemoryBuffer(size, &buffer));
    CHECK(IMFMediaBuffer_Lock(buffer, &bits, NULL, NULL));
    for (int y = 0; y < HEIGHT; ++y) {
        for (int x = 0; x < WIDTH; ++x) {
            *bits++ = (x + number * 3) & 255;
            *bits++ = (y * 2) & 255;
            *bits++ = (x < (number * 4) % WIDTH) ? 225 : 32;
            *bits++ = 255;
        }
    }
    CHECK(IMFMediaBuffer_Unlock(buffer));
    CHECK(IMFMediaBuffer_SetCurrentLength(buffer, size));
    CHECK(MFCreateSample(&sample));
    CHECK(IMFSample_AddBuffer(sample, buffer));
    CHECK(IMFSample_SetSampleTime(sample, (LONGLONG)number * 10000000 / FPS));
    CHECK(IMFSample_SetSampleDuration(sample, 10000000 / FPS));
    CHECK(IMFSinkWriter_WriteSample(writer, stream, sample));
done:
    if (sample) IMFSample_Release(sample);
    if (buffer) IMFMediaBuffer_Release(buffer);
    return hr;
}

int main(int argc, char **argv) {
    if (argc != 3 || (strcmp(argv[1], "WMV3") && strcmp(argv[1], "WVC1"))) return 2;
    WCHAR path[1024];
    if (!MultiByteToWideChar(CP_UTF8, 0, argv[2], -1, path, 1024)) return 2;
    HRESULT hr = CoInitializeEx(NULL, COINIT_MULTITHREADED);
    if (FAILED(hr)) return 3;
    hr = MFStartup(MF_VERSION, MFSTARTUP_FULL);
    if (FAILED(hr)) { CoUninitialize(); return 3; }
    IMFSinkWriter *writer = NULL;
    IMFMediaType *encoded = NULL, *raw = NULL;
    DWORD stream;
    CHECK(MFCreateSinkWriterFromURL(path, NULL, NULL, &writer));
    CHECK(video_type(&encoded, !strcmp(argv[1], "WMV3") ? &MFVideoFormat_WMV3 : &MFVideoFormat_WVC1));
    CHECK(IMFMediaType_SetUINT32(encoded, &MF_MT_AVG_BITRATE, 600000));
    CHECK(IMFSinkWriter_AddStream(writer, encoded, &stream));
    CHECK(video_type(&raw, &MFVideoFormat_RGB32));
    CHECK(IMFSinkWriter_SetInputMediaType(writer, stream, raw, NULL));
    CHECK(IMFSinkWriter_BeginWriting(writer));
    for (int i = 0; i < 8 * FPS; i++) CHECK(frame(writer, stream, i));
    CHECK(IMFSinkWriter_Finalize(writer));
done:
    if (raw) IMFMediaType_Release(raw);
    if (encoded) IMFMediaType_Release(encoded);
    if (writer) IMFSinkWriter_Release(writer);
    MFShutdown();
    CoUninitialize();
    return FAILED(hr);
}
