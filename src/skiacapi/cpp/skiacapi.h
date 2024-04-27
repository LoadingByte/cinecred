#ifndef CAPI
#define CAPI
#endif

#ifdef __cplusplus
class SkCanvas;
class SkColorSpace;
class SkData;
class SkDocument;
class SkDynamicMemoryWStream;
class SkPaint;
class SkRefCnt;
class SkShader;
class SkSVGDOM;
extern "C" {
#else
#include <stdbool.h>
typedef void SkCanvas;
typedef void SkColorSpace;
typedef void SkData;
typedef void SkDocument;
typedef void SkDynamicMemoryWStream;
typedef void SkPaint;
typedef void SkRefCnt;
typedef void SkShader;
typedef void SkSVGDOM;
#endif

#define MATRIX23_PARAMETERS float m00, float m10, float m01, float m11, float m02, float m12
#define MATRIX33_PARAMETERS float m00, float m01, float m02, float m10, float m11, float m12, float m20, float m21, float m22
#define TRC_PARAMETERS float g, float a, float b, float c, float d, float e, float f
#define IMAGE_PARAMETERS int w, int h, unsigned char colorType, unsigned char alphaType, SkColorSpace* colorSpace, void* pixels, long long rowBytes

// const.cpp

CAPI unsigned char SkColorType_RGBA_F32(void);
CAPI unsigned char SkColorType_A16_unorm(void);

CAPI unsigned char SkAlphaType_Opaque(void);
CAPI unsigned char SkAlphaType_Premul(void);
CAPI unsigned char SkAlphaType_Unpremul(void);

CAPI unsigned char SkTileMode_Clamp(void);
CAPI unsigned char SkTileMode_Decal(void);

CAPI unsigned char SkFilterMode_Nearest(void);
CAPI unsigned char SkFilterMode_Linear(void);

CAPI unsigned char SkPathVerb_Move(void);
CAPI unsigned char SkPathVerb_Line(void);
CAPI unsigned char SkPathVerb_Quad(void);
CAPI unsigned char SkPathVerb_Cubic(void);
CAPI unsigned char SkPathVerb_Close(void);

CAPI unsigned char SkPaintCap_Butt(void);
CAPI unsigned char SkPaintCap_Round(void);
CAPI unsigned char SkPaintCap_Square(void);

CAPI unsigned char SkPaintJoin_Miter(void);
CAPI unsigned char SkPaintJoin_Round(void);
CAPI unsigned char SkPaintJoin_Bevel(void);

CAPI unsigned char SkBlendMode_Clear(void);
CAPI unsigned char SkBlendMode_Src(void);
CAPI unsigned char SkBlendMode_Dst(void);
CAPI unsigned char SkBlendMode_SrcOver(void);
CAPI unsigned char SkBlendMode_DstOver(void);
CAPI unsigned char SkBlendMode_SrcIn(void);
CAPI unsigned char SkBlendMode_DstIn(void);
CAPI unsigned char SkBlendMode_SrcOut(void);
CAPI unsigned char SkBlendMode_DstOut(void);
CAPI unsigned char SkBlendMode_SrcATop(void);
CAPI unsigned char SkBlendMode_DstATop(void);
CAPI unsigned char SkBlendMode_Xor(void);
CAPI unsigned char SkBlendMode_Plus(void);
CAPI unsigned char SkBlendMode_Modulate(void);
CAPI unsigned char SkBlendMode_Screen(void);
CAPI unsigned char SkBlendMode_Overlay(void);
CAPI unsigned char SkBlendMode_Darken(void);
CAPI unsigned char SkBlendMode_Lighten(void);
CAPI unsigned char SkBlendMode_ColorDodge(void);
CAPI unsigned char SkBlendMode_ColorBurn(void);
CAPI unsigned char SkBlendMode_HardLight(void);
CAPI unsigned char SkBlendMode_SoftLight(void);
CAPI unsigned char SkBlendMode_Difference(void);
CAPI unsigned char SkBlendMode_Exclusion(void);
CAPI unsigned char SkBlendMode_Multiply(void);
CAPI unsigned char SkBlendMode_Hue(void);
CAPI unsigned char SkBlendMode_Saturation(void);
CAPI unsigned char SkBlendMode_Color(void);
CAPI unsigned char SkBlendMode_Luminosity(void);

CAPI unsigned char SkGradientShaderInterpolationColorSpace_Destination(void);
CAPI unsigned char SkGradientShaderInterpolationColorSpace_OKLab(void);
CAPI unsigned char SkGradientShaderInterpolationColorSpace_SRGB(void);

CAPI const float* SkNamedTransferFn_SRGB(void);
CAPI const float* SkNamedTransferFn_Rec2020(void);
CAPI const float* SkNamedTransferFn_PQ(void);
CAPI const float* SkNamedTransferFn_HLG(void);

CAPI const float* SkNamedGamut_SRGB(void);
CAPI const float* SkNamedGamut_AdobeRGB(void);
CAPI const float* SkNamedGamut_DisplayP3(void);
CAPI const float* SkNamedGamut_Rec2020(void);

// misc.cpp

CAPI void SkRefCnt_unref(SkRefCnt* object);

CAPI void SkData_unref(SkData* data);
CAPI long long SkData_size(SkData* data);
CAPI const void* SkData_data(SkData* data);

CAPI SkDynamicMemoryWStream* SkDynamicMemoryWStream_New(void);
CAPI void SkDynamicMemoryWStream_delete(SkDynamicMemoryWStream* stream);
CAPI SkData* SkDynamicMemoryWStream_detachAsData(SkDynamicMemoryWStream* stream);

CAPI SkColorSpace* SkColorSpace_MakeRGB(TRC_PARAMETERS, MATRIX33_PARAMETERS);
CAPI SkData* SkICC_SkWriteICCProfile(TRC_PARAMETERS, MATRIX33_PARAMETERS);

// svg.cpp

typedef bool (*loadImage_t)(const char path[], const char name[], const char id[], int* w, int* h, unsigned char* colorType, unsigned char* alphaType, SkColorSpace** colorSpace, void** pixels, long long* rowBytes);

CAPI SkSVGDOM* SkSVGDOM_Make(char* str, long long len, loadImage_t loadImage);
CAPI void SkSVGDOM_containerSize(SkSVGDOM* dom, float wh[2]);
CAPI void SkSVGDOM_setContainerSize(SkSVGDOM* dom, float w, float h);
CAPI bool SkSVGDOM_getViewBox(SkSVGDOM* dom, float box[4]);
CAPI void SkSVGDOM_render(SkSVGDOM* dom, SkCanvas* canvas);

// canvas.cpp

typedef struct Path {
    unsigned char* verbs;
    int verbCount;
    float* points;
    int pointCount;
    bool isEvenOdd;
} Path;

CAPI SkCanvas* SkCanvas_MakeRasterDirect(IMAGE_PARAMETERS);
CAPI SkCanvas* SkSVGCanvas_Make(SkDynamicMemoryWStream* stream, float x, float y, float w, float h);
CAPI void SkCanvas_delete(SkCanvas* canvas);

CAPI SkDocument* SkPDF_MakeDocument(SkDynamicMemoryWStream* stream);
CAPI SkCanvas* SkDocument_beginPage(SkDocument* document, float w, float h);
CAPI void SkDocument_endPage(SkDocument* document);

CAPI void SkCanvas_save(SkCanvas* canvas);
CAPI void SkCanvas_saveLayer(SkCanvas* canvas, bool passBounds, float x, float y, float w, float h, SkPaint* paint);
CAPI void SkCanvas_restore(SkCanvas* canvas);
CAPI void SkCanvas_setMatrix(SkCanvas* canvas, MATRIX23_PARAMETERS);
CAPI void SkCanvas_clipRect(SkCanvas* canvas, float x, float y, float w, float h, bool doAntiAlias);
CAPI void SkCanvas_clipPath(SkCanvas* canvas, Path* path, bool doAntiAlias);
CAPI void SkCanvas_drawPath(SkCanvas* canvas, Path* path, SkPaint* paint);
CAPI void SkCanvas_drawImage(SkCanvas* canvas, IMAGE_PARAMETERS, float x, float y, unsigned char filterMode, SkPaint* paint);

// paint.cpp

CAPI SkPaint* SkPaint_New(void);
CAPI void SkPaint_delete(SkPaint* paint);
CAPI void SkPaint_setAntiAlias(SkPaint* paint, bool antiAlias);
CAPI void SkPaint_setStroke(SkPaint* paint, bool stroke);
CAPI void SkPaint_setStrokeProperties(SkPaint* paint, float width, unsigned char cap, unsigned char join, float miterlimit);
CAPI void SkPaint_setColor(SkPaint* paint, float c1, float c2, float c3, float a, SkColorSpace* colorSpace);
CAPI void SkPaint_setAlpha(SkPaint* paint, float a);
CAPI void SkPaint_setBlendMode(SkPaint* paint, unsigned char blendMode);
CAPI void SkPaint_setDashPathEffect(SkPaint* paint, float intervals[], int count, float phase);
CAPI void SkPaint_setShaderMaskFilter(SkPaint* paint, SkShader* shader);
CAPI void SkPaint_setShader(SkPaint* paint, SkShader* shader);
CAPI void SkPaint_setBlurImageFilter(SkPaint* paint, float sigmaX, float sigmaY);

CAPI SkShader* SkGradientShader_MakeLinear(float x1, float y1, float x2, float y2, float colors[], SkColorSpace* colorSpace, float pos[], int count, unsigned char tileMode, unsigned char interpolationColorSpace);
CAPI SkShader* SkImage_makeShader(IMAGE_PARAMETERS, unsigned char tileModeX, unsigned char tileModeY, unsigned char filterMode, MATRIX23_PARAMETERS);

#ifdef __cplusplus
}
#endif
