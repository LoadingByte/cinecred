#ifndef CAPI
#define CAPI
#endif

#ifdef __cplusplus
class IDeckLink;
class IDeckLinkDiscovery;
class IDeckLinkDeviceNotificationCallback;
class IDeckLinkDisplayMode;
class IDeckLinkDisplayModeIterator;
class IDeckLinkVideoFrame;
class IDeckLinkOutput;
class IDeckLinkProfileAttributes;
class IDeckLinkVideoOutputCallback;
class IUnknown;
extern "C" {
#else
#include <stdbool.h>
typedef void IDeckLink;
typedef void IDeckLinkDiscovery;
typedef void IDeckLinkDeviceNotificationCallback;
typedef void IDeckLinkDisplayMode;
typedef void IDeckLinkDisplayModeIterator;
typedef void IDeckLinkVideoFrame;
typedef void IDeckLinkOutput;
typedef void IDeckLinkProfileAttributes;
typedef void IDeckLinkVideoOutputCallback;
typedef void IUnknown;
#endif

#define FRAME_METADATA int eotf, double rx, double ry, double gx, double gy, double bx, double by, double wx, double wy, double maxDML, double minDML, double maxCLL, double maxFALL, int cs

typedef void (*deviceNotificationCallback_t)(IDeckLink* device);
typedef void (*scheduledFrameCompletionCallback_t)(IDeckLinkVideoFrame* frame, int result);

CAPI int PixelFormat_8BitBGRA(void);
CAPI int PixelFormat_10BitRGB(void);

CAPI int FieldDominance_LowerFieldFirst(void);
CAPI int FieldDominance_UpperFieldFirst(void);
CAPI int FieldDominance_ProgressiveFrame(void);
CAPI int FieldDominance_ProgressiveSegmentedFrame(void);

CAPI int DisplayModeFlag_ColorspaceRec601(void);
CAPI int DisplayModeFlag_ColorspaceRec709(void);
CAPI int DisplayModeFlag_ColorspaceRec2020(void);

CAPI int Colorspace_Rec601(void);
CAPI int Colorspace_Rec709(void);
CAPI int Colorspace_Rec2020(void);

CAPI bool initDeckLinkAPI(void);

CAPI IDeckLinkDeviceNotificationCallback* IDeckLinkDeviceNotificationCallback_Create(deviceNotificationCallback_t arrivedCallback, deviceNotificationCallback_t removedCallback);
CAPI IDeckLinkVideoOutputCallback* IDeckLinkVideoOutputCallback_Create(scheduledFrameCompletionCallback_t callback);
CAPI IDeckLinkVideoFrame* IDeckLinkVideoFrame_Create(int width, int height, int rowBytes, int pixelFormat, FRAME_METADATA, void* bytes);

CAPI void IUnknown_AddRef(IUnknown* object);
CAPI void IUnknown_Release(IUnknown* object);

CAPI IDeckLinkDiscovery* IDeckLinkDiscovery_Create(void);
CAPI bool IDeckLinkDiscovery_InstallDeviceNotifications(IDeckLinkDiscovery* discovery, IDeckLinkDeviceNotificationCallback* callback);

CAPI bool IDeckLink_GetDisplayName(IDeckLink* deckLink, char* str, long long len);
CAPI IDeckLinkProfileAttributes* IDeckLink_QueryIDeckLinkProfileAttributes(IDeckLink* deckLink);
CAPI IDeckLinkOutput* IDeckLink_QueryIDeckLinkOutput(IDeckLink* deckLink);

CAPI bool IDeckLinkProfileAttributes_GetDeviceHandle(IDeckLinkProfileAttributes* attributes, char* str, long long len);
CAPI bool IDeckLinkProfileAttributes_IsActive(IDeckLinkProfileAttributes* attributes);
CAPI bool IDeckLinkProfileAttributes_SupportsPlayback(IDeckLinkProfileAttributes* attributes);

CAPI IDeckLinkDisplayModeIterator* IDeckLinkOutput_GetDisplayModeIterator(IDeckLinkOutput* output);
CAPI bool IDeckLinkOutput_DoesSupportVideoMode(IDeckLinkOutput* output, int mode, int pixelFormat);
CAPI bool IDeckLinkOutput_EnableVideoOutput(IDeckLinkOutput* output, int mode);
CAPI bool IDeckLinkOutput_DisableVideoOutput(IDeckLinkOutput* output);
CAPI bool IDeckLinkOutput_StartScheduledPlayback(IDeckLinkOutput* output, long long startTime, long long timeScale, double speed);
CAPI bool IDeckLinkOutput_StopScheduledPlayback(IDeckLinkOutput* output, long long stopTime, long long timeScale);
CAPI bool IDeckLinkOutput_SetScheduledFrameCompletionCallback(IDeckLinkOutput* output, IDeckLinkVideoOutputCallback* callback);
CAPI bool IDeckLinkOutput_DisplayVideoFrameSync(IDeckLinkOutput* output, IDeckLinkVideoFrame* frame);
CAPI bool IDeckLinkOutput_ScheduleVideoFrame(IDeckLinkOutput* output, IDeckLinkVideoFrame* frame, long long displayTime, long long displayDuration, long long timeScale);

CAPI IDeckLinkDisplayMode* IDeckLinkDisplayModeIterator_Next(IDeckLinkDisplayModeIterator* iterator);

CAPI bool IDeckLinkDisplayMode_GetName(IDeckLinkDisplayMode* mode, char* str, long long len);
CAPI int IDeckLinkDisplayMode_GetDisplayMode(IDeckLinkDisplayMode* mode);
CAPI int IDeckLinkDisplayMode_GetWidth(IDeckLinkDisplayMode* mode);
CAPI int IDeckLinkDisplayMode_GetHeight(IDeckLinkDisplayMode* mode);
CAPI long long IDeckLinkDisplayMode_GetFrameRate(IDeckLinkDisplayMode* mode);
CAPI int IDeckLinkDisplayMode_GetFieldDominance(IDeckLinkDisplayMode* mode);
CAPI int IDeckLinkDisplayMode_GetFlags(IDeckLinkDisplayMode* mode);

#ifdef __cplusplus
}
#endif
