#include "include/core/SkColorFilter.h"
#include "include/core/SkMaskFilter.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPathEffect.h"
#include "include/core/SkPixmap.h"
#include "include/effects/SkDashPathEffect.h"
#include "include/effects/SkGradientShader.h"
#include "include/effects/SkImageFilters.h"
#include "include/effects/SkShaderMaskFilter.h"
#include "skiacapi.h"

SkPaint* SkPaint_New(void) {
    return new SkPaint;
}

void SkPaint_delete(SkPaint* paint) {
    delete paint;
}

void SkPaint_setAntiAlias(SkPaint* paint, bool antiAlias) {
    paint->setAntiAlias(antiAlias);
}

void SkPaint_setStroke(SkPaint* paint, bool stroke) {
    paint->setStroke(stroke);
}

void SkPaint_setStrokeProperties(SkPaint* paint, float width, unsigned char cap, unsigned char join, float miterlimit) {
    paint->setStrokeWidth(width);
    paint->setStrokeCap(static_cast<SkPaint::Cap>(cap));
    paint->setStrokeJoin(static_cast<SkPaint::Join>(join));
    paint->setStrokeMiter(miterlimit);
}

void SkPaint_setColor(SkPaint* paint, float c1, float c2, float c3, float a, SkColorSpace* colorSpace) {
    paint->setColor({ c1, c2, c3, a }, colorSpace);
}

void SkPaint_setAlpha(SkPaint* paint, float a) {
    paint->setAlphaf(a);
}

void SkPaint_setBlendMode(SkPaint* paint, unsigned char blendMode) {
    paint->setBlendMode(static_cast<SkBlendMode>(blendMode));
}

void SkPaint_setDashPathEffect(SkPaint* paint, float intervals[], int count, float phase) {
    paint->setPathEffect(SkDashPathEffect::Make(intervals, count, phase));
}

void SkPaint_setShaderMaskFilter(SkPaint* paint, SkShader* shader) {
    paint->setMaskFilter(SkShaderMaskFilter::Make(sk_ref_sp(shader)));
}

void SkPaint_setShader(SkPaint* paint, SkShader* shader) {
    paint->setShader(sk_ref_sp(shader));
}

void SkPaint_setBlurImageFilter(SkPaint* paint, float sigmaX, float sigmaY) {
    paint->setImageFilter(SkImageFilters::Blur(sigmaX, sigmaY, nullptr));
}

SkShader* SkGradientShader_MakeLinear(float x1, float y1, float x2, float y2, float colors[], SkColorSpace* colorSpace, float pos[], int count, unsigned char tileMode, unsigned char interpolationColorSpace) {
    SkPoint pts[] = {{ x1, y1 }, { x2, y2 }};
    SkGradientShader::Interpolation interp;
    interp.fColorSpace = static_cast<SkGradientShader::Interpolation::ColorSpace>(interpolationColorSpace);
    return SkGradientShader::MakeLinear(pts, reinterpret_cast<SkColor4f*>(colors), sk_ref_sp(colorSpace), pos, count, static_cast<SkTileMode>(tileMode), interp, nullptr).release();
}

SkShader* SkImage_makeShader(IMAGE_PARAMETERS, unsigned char tileModeX, unsigned char tileModeY, unsigned char filterMode, MATRIX23_PARAMETERS) {
    sk_sp<SkImage> image = SkImages::RasterFromPixmap(SkPixmap(
        SkImageInfo::Make(w, h, static_cast<SkColorType>(colorType), static_cast<SkAlphaType>(alphaType), sk_ref_sp(colorSpace)),
        pixels, rowBytes
    ), nullptr, nullptr);
    return image->makeShader(
        static_cast<SkTileMode>(tileModeX), static_cast<SkTileMode>(tileModeY), SkSamplingOptions(static_cast<SkFilterMode>(filterMode)),
        SkMatrix::MakeAll(m00, m01, m02, m10, m11, m12, 0, 0, 1)
    ).release();
}
