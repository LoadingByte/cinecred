#include <atomic>
#include "decklinkcapi.h"


#if defined(_WIN32)

#include <comutil.h>
#pragma comment(lib, "ole32.lib")
#pragma comment(lib, "comsuppw.lib")
#include "DeckLinkAPI.h"
typedef BOOL nativeBool_t;
typedef BSTR nativeStr_t;
static void rebuildString(nativeStr_t nativeStr, char* str, long long len) {
    char* chars = _com_util::ConvertBSTRToString(nativeStr);
    strncpy(str, chars, len);
    ::SysFreeString(nativeStr);
    delete[] chars;
    str[len - 1] = '\0';
}

#elif defined(__APPLE_CC__)

#include "sdk/mac/DeckLinkAPI.h"
typedef bool nativeBool_t;
typedef CFStringRef nativeStr_t;
static void rebuildString(nativeStr_t nativeStr, char* str, long long len) {
    CFStringGetCString(nativeStr, str, len, kCFStringEncodingUTF8);
    CFRelease(nativeStr);
    str[len - 1] = '\0';
}

#elif defined(__linux__)

#include <cstdlib>
#include <cstring>
#include "sdk/linux/DeckLinkAPI.h"
typedef bool nativeBool_t;
typedef const char* nativeStr_t;
static void rebuildString(nativeStr_t nativeStr, char* str, long long len) {
    strncpy(str, nativeStr, len);
    free(const_cast<char*>(nativeStr));
    str[len - 1] = '\0';
}

#endif


class DeviceNotificationCallbackImpl : public IDeckLinkDeviceNotificationCallback {
public:
    DeviceNotificationCallbackImpl(deviceNotificationCallback_t arrivedCallback, deviceNotificationCallback_t removedCallback) :
        fArrivedCallback(arrivedCallback), fRemovedCallback(removedCallback) {}
    HRESULT DeckLinkDeviceArrived(IDeckLink* device) {
        fArrivedCallback(device);
        return S_OK;
    }
    HRESULT DeckLinkDeviceRemoved(IDeckLink* device) {
        fRemovedCallback(device);
        return S_OK;
    }
    HRESULT QueryInterface(REFIID, LPVOID* ppv) {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG AddRef() { return ++fRefCount; }
    ULONG Release() {
        ULONG refCount = --fRefCount;
        if (refCount == 0)
            delete this;
        return refCount;
    }
private:
    deviceNotificationCallback_t fArrivedCallback;
    deviceNotificationCallback_t fRemovedCallback;
    std::atomic<ULONG> fRefCount = 1;
};


class VideoOutputCallbackImpl : public IDeckLinkVideoOutputCallback {
public:
    VideoOutputCallbackImpl(scheduledFrameCompletionCallback_t callback) : fCallback(callback) {}
    HRESULT ScheduledFrameCompleted(IDeckLinkVideoFrame* frame, BMDOutputFrameCompletionResult result) {
        fCallback(frame, result);
        return S_OK;
    }
    HRESULT ScheduledPlaybackHasStopped(void) { return S_OK; }
    HRESULT QueryInterface(REFIID, LPVOID* ppv) {
        *ppv = nullptr;
        return E_NOINTERFACE;
    }
    ULONG AddRef() { return ++fRefCount; }
    ULONG Release() {
        ULONG refCount = --fRefCount;
        if (refCount == 0)
            delete this;
        return refCount;
    }
private:
    scheduledFrameCompletionCallback_t fCallback;
    std::atomic<ULONG> fRefCount = 1;
};


class VideoFrameImpl : public IDeckLinkVideoFrame, public IDeckLinkVideoFrameMetadataExtensions {
public:
    VideoFrameImpl(int width, int height, int rowBytes, BMDPixelFormat pixelFormat, FRAME_METADATA, void* bytes) :
        fWidth(width), fHeight(height), fRowBytes(rowBytes), fPixelFormat(pixelFormat),
        fEOTF(eotf), fRX(rx), fRY(ry), fGX(gx), fGY(gy), fBX(bx), fBY(by), fWX(wx), fWY(wy), fMaxDML(maxDML), fMinDML(minDML), fMaxCLL(maxCLL), fMaxFALL(maxFALL), fCS(cs),
        fBytes(bytes) {}
    // IDeckLinkVideoFrame
    long GetWidth(void) { return fWidth; }
    long GetHeight(void) { return fHeight; }
    long GetRowBytes(void) { return fRowBytes; }
    BMDPixelFormat GetPixelFormat(void) { return fPixelFormat; }
    BMDFrameFlags GetFlags(void) { return bmdFrameContainsHDRMetadata; }
    HRESULT GetBytes(void** buffer) {
        *buffer = fBytes;
        return S_OK;
    }
    HRESULT GetTimecode(BMDTimecodeFormat, IDeckLinkTimecode**) { return S_FALSE; }
    HRESULT GetAncillaryData(IDeckLinkVideoFrameAncillary**) { return S_FALSE; }
    // IDeckLinkVideoFrameMetadataExtensions
    HRESULT GetInt(BMDDeckLinkFrameMetadataID metadataID, int64_t* value) {
        switch (metadataID) {
            case bmdDeckLinkFrameMetadataHDRElectroOpticalTransferFunc:
                *value = fEOTF;
                return S_OK;
            case bmdDeckLinkFrameMetadataColorspace:
                *value = fCS;
                return S_OK;
            default:
                *value = 0;
                return E_INVALIDARG;
        }
    }
    HRESULT GetFloat(BMDDeckLinkFrameMetadataID metadataID, double* value) {
        switch (metadataID) {
            case bmdDeckLinkFrameMetadataHDRDisplayPrimariesRedX:
                *value = fRX;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRDisplayPrimariesRedY:
                *value = fRY;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRDisplayPrimariesGreenX:
                *value = fGX;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRDisplayPrimariesGreenY:
                *value = fGY;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRDisplayPrimariesBlueX:
                *value = fBX;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRDisplayPrimariesBlueY:
                *value = fBY;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRWhitePointX:
                *value = fWX;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRWhitePointY:
                *value = fWY;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRMaxDisplayMasteringLuminance:
                *value = fMaxDML;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRMinDisplayMasteringLuminance:
                *value = fMinDML;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRMaximumContentLightLevel:
                *value = fMaxCLL;
                return S_OK;
            case bmdDeckLinkFrameMetadataHDRMaximumFrameAverageLightLevel:
                *value = fMaxFALL;
                return S_OK;
            default:
                *value = 0;
                return E_INVALIDARG;
        }
    }
    HRESULT GetFlag(BMDDeckLinkFrameMetadataID, nativeBool_t* value) {
        *value = false;
        return E_INVALIDARG;
    }
    HRESULT GetString(BMDDeckLinkFrameMetadataID, nativeStr_t* value) {
        *value = nullptr;
        return E_INVALIDARG;
    }
    HRESULT GetBytes(BMDDeckLinkFrameMetadataID, void*, unsigned int* bufferSize) {
        *bufferSize = 0;
        return E_INVALIDARG;
    }
    // IUnknown
    HRESULT QueryInterface(REFIID iid, LPVOID* ppv) {
        if (std::memcmp(&iid, &IID_IDeckLinkVideoFrameMetadataExtensions, sizeof(REFIID)) == 0) {
            AddRef();
            *ppv = static_cast<IDeckLinkVideoFrameMetadataExtensions*>(this);
            return S_OK;
        } else {
            *ppv = nullptr;
            return E_NOINTERFACE;
        }
    }
    ULONG AddRef() { return ++fRefCount; }
    ULONG Release() {
        ULONG refCount = --fRefCount;
        if (refCount == 0)
            delete this;
        return refCount;
    }
private:
    int fWidth;
    int fHeight;
    int fRowBytes;
    BMDPixelFormat fPixelFormat;
    int fEOTF;
    double fRX, fRY, fGX, fGY, fBX, fBY, fWX, fWY;
    double fMaxDML, fMinDML, fMaxCLL, fMaxFALL;
    int fCS;
    void* fBytes;
    std::atomic<ULONG> fRefCount = 1;
};


#if WIN32

bool initDeckLinkAPI(void) { return SUCCEEDED(CoInitializeEx(nullptr, COINIT_MULTITHREADED | COINIT_DISABLE_OLE1DDE)); }
IDeckLinkDiscovery* IDeckLinkDiscovery_Create(void) {
    IDeckLinkDiscovery* discovery;
    return CoCreateInstance(CLSID_CDeckLinkDiscovery, nullptr, CLSCTX_ALL, IID_IDeckLinkDiscovery, (void**) &discovery) == S_OK ? discovery : nullptr;
}

#elif __APPLE_CC__ || defined(__linux__)

bool initDeckLinkAPI(void) { return true; }
IDeckLinkDiscovery* IDeckLinkDiscovery_Create(void) { return CreateDeckLinkDiscoveryInstance(); }

#endif


int PixelFormat_8BitBGRA(void) { return bmdFormat8BitBGRA; }
int PixelFormat_10BitRGB(void) { return bmdFormat10BitRGB; }

int FieldDominance_LowerFieldFirst(void) { return bmdLowerFieldFirst; }
int FieldDominance_UpperFieldFirst(void) { return bmdUpperFieldFirst; }
int FieldDominance_ProgressiveFrame(void) { return bmdProgressiveFrame; }
int FieldDominance_ProgressiveSegmentedFrame(void) { return bmdProgressiveSegmentedFrame; }

int DisplayModeFlag_ColorspaceRec601(void) { return bmdDisplayModeColorspaceRec601; }
int DisplayModeFlag_ColorspaceRec709(void) { return bmdDisplayModeColorspaceRec709; }
int DisplayModeFlag_ColorspaceRec2020(void) { return bmdDisplayModeColorspaceRec2020; }

int Colorspace_Rec601(void) { return bmdColorspaceRec601; }
int Colorspace_Rec709(void) { return bmdColorspaceRec709; }
int Colorspace_Rec2020(void) { return bmdColorspaceRec2020; }


IDeckLinkDeviceNotificationCallback* IDeckLinkDeviceNotificationCallback_Create(deviceNotificationCallback_t arrivedCallback, deviceNotificationCallback_t removedCallback) {
    return new DeviceNotificationCallbackImpl(arrivedCallback, removedCallback);
}

IDeckLinkVideoOutputCallback* IDeckLinkVideoOutputCallback_Create(scheduledFrameCompletionCallback_t callback) {
    return new VideoOutputCallbackImpl(callback);
}

IDeckLinkVideoFrame* IDeckLinkVideoFrame_Create(int width, int height, int rowBytes, int pixelFormat, FRAME_METADATA, void* bytes) {
    return new VideoFrameImpl(width, height, rowBytes, static_cast<BMDPixelFormat>(pixelFormat), eotf, rx, ry, gx, gy, bx, by, wx, wy, maxDML, minDML, maxCLL, maxFALL, cs, bytes);
}


void IUnknown_AddRef(IUnknown* object) {
    object->AddRef();
}

void IUnknown_Release(IUnknown* object) {
    object->Release();
}


bool IDeckLinkDiscovery_InstallDeviceNotifications(IDeckLinkDiscovery* discovery, IDeckLinkDeviceNotificationCallback* callback) {
    return discovery->InstallDeviceNotifications(callback) == S_OK;
}


bool IDeckLink_GetDisplayName(IDeckLink* deckLink, char* str, long long len) {
    nativeStr_t nativeStr;
    if (deckLink->GetDisplayName(&nativeStr) != S_OK)
        return false;
    rebuildString(nativeStr, str, len);
    return true;
}

IDeckLinkProfileAttributes* IDeckLink_QueryIDeckLinkProfileAttributes(IDeckLink* deckLink) {
    IDeckLinkProfileAttributes* attributes;
    return deckLink->QueryInterface(IID_IDeckLinkProfileAttributes, (void**) &attributes) == S_OK ? attributes : nullptr;
}

IDeckLinkOutput* IDeckLink_QueryIDeckLinkOutput(IDeckLink* deckLink) {
    IDeckLinkOutput* output;
    return deckLink->QueryInterface(IID_IDeckLinkOutput, (void**) &output) == S_OK ? output : nullptr;
}


bool IDeckLinkProfileAttributes_GetDeviceHandle(IDeckLinkProfileAttributes* attributes, char* str, long long len) {
    nativeStr_t nativeStr;
    if (attributes->GetString(BMDDeckLinkDeviceHandle, &nativeStr) != S_OK)
        return false;
    rebuildString(nativeStr, str, len);
    return true;
}

bool IDeckLinkProfileAttributes_IsActive(IDeckLinkProfileAttributes* attributes) {
    int64_t duplexMode;
    return attributes->GetInt(BMDDeckLinkDuplex, &duplexMode) == S_OK ? duplexMode != bmdDuplexInactive : false;
}

bool IDeckLinkProfileAttributes_SupportsPlayback(IDeckLinkProfileAttributes* attributes) {
    int64_t ioSupport;
    return attributes->GetInt(BMDDeckLinkVideoIOSupport, &ioSupport) == S_OK ? ioSupport & bmdDeviceSupportsPlayback : false;
}


IDeckLinkDisplayModeIterator* IDeckLinkOutput_GetDisplayModeIterator(IDeckLinkOutput* output) {
    IDeckLinkDisplayModeIterator* iterator;
    return output->GetDisplayModeIterator(&iterator) == S_OK ? iterator : nullptr;
}

bool IDeckLinkOutput_DoesSupportVideoMode(IDeckLinkOutput* output, int mode, int pixelFormat) {
    nativeBool_t supported;
    return output->DoesSupportVideoMode(bmdVideoConnectionUnspecified, static_cast<BMDDisplayMode>(mode), static_cast<BMDPixelFormat>(pixelFormat), bmdNoVideoOutputConversion, bmdSupportedVideoModeDefault, nullptr, &supported) == S_OK && supported;
}

bool IDeckLinkOutput_EnableVideoOutput(IDeckLinkOutput* output, int mode) {
    return output->EnableVideoOutput(static_cast<BMDDisplayMode>(mode), bmdVideoOutputFlagDefault) == S_OK;
}

bool IDeckLinkOutput_DisableVideoOutput(IDeckLinkOutput* output) {
    return output->DisableVideoOutput() == S_OK;
}

bool IDeckLinkOutput_StartScheduledPlayback(IDeckLinkOutput* output, long long startTime, long long timeScale, double speed) {
    return output->StartScheduledPlayback(startTime, timeScale, speed) == S_OK;
}

bool IDeckLinkOutput_StopScheduledPlayback(IDeckLinkOutput* output, long long stopTime, long long timeScale) {
    return output->StopScheduledPlayback(stopTime, nullptr, timeScale) == S_OK;
}

bool IDeckLinkOutput_SetScheduledFrameCompletionCallback(IDeckLinkOutput* output, IDeckLinkVideoOutputCallback* callback) {
    return output->SetScheduledFrameCompletionCallback(callback) == S_OK;
}

bool IDeckLinkOutput_DisplayVideoFrameSync(IDeckLinkOutput* output, IDeckLinkVideoFrame* frame) {
    return output->DisplayVideoFrameSync(frame) == S_OK;
}

bool IDeckLinkOutput_ScheduleVideoFrame(IDeckLinkOutput* output, IDeckLinkVideoFrame* frame, long long displayTime, long long displayDuration, long long timeScale) {
    return output->ScheduleVideoFrame(frame, displayTime, displayDuration, timeScale) == S_OK;
}


IDeckLinkDisplayMode* IDeckLinkDisplayModeIterator_Next(IDeckLinkDisplayModeIterator* iterator) {
    IDeckLinkDisplayMode* mode;
    return iterator->Next(&mode) == S_OK ? mode : nullptr;
}


bool IDeckLinkDisplayMode_GetName(IDeckLinkDisplayMode* mode, char* str, long long len) {
    nativeStr_t nativeStr;
    if (mode->GetName(&nativeStr) != S_OK)
        return false;
    rebuildString(nativeStr, str, len);
    return true;
}

int IDeckLinkDisplayMode_GetDisplayMode(IDeckLinkDisplayMode* mode) { return mode->GetDisplayMode(); }
int IDeckLinkDisplayMode_GetWidth(IDeckLinkDisplayMode* mode) { return mode->GetWidth(); }
int IDeckLinkDisplayMode_GetHeight(IDeckLinkDisplayMode* mode) { return mode->GetHeight(); }

long long IDeckLinkDisplayMode_GetFrameRate(IDeckLinkDisplayMode* mode) {
    BMDTimeValue frameDuration, timeScale;
    if (mode->GetFrameRate(&frameDuration, &timeScale) != S_OK)
        return -1;
    return (frameDuration << 32) | timeScale;
}

int IDeckLinkDisplayMode_GetFieldDominance(IDeckLinkDisplayMode* mode) { return mode->GetFieldDominance(); }
int IDeckLinkDisplayMode_GetFlags(IDeckLinkDisplayMode* mode) { return mode->GetFlags(); }
