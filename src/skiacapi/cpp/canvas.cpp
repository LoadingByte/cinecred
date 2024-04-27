#include "include/core/SkCanvas.h"
#include "include/core/SkColorSpace.h"
#include "include/core/SkDocument.h"
#include "include/core/SkImage.h"
#include "include/core/SkPath.h"
#include "include/core/SkPixmap.h"
#include "include/docs/SkPDFDocument.h"
#include "include/core/SkStream.h"
#include "include/svg/SkSVGCanvas.h"
#include "skiacapi.h"

static SkPath convertPath(Path* path) {
    return SkPath::Make(
        reinterpret_cast<SkPoint*>(path->points), path->pointCount,
        path->verbs, path->verbCount,
        nullptr, 0,
        path->isEvenOdd ? SkPathFillType::kEvenOdd : SkPathFillType::kWinding,
        /* isVolatile = */ true
    );
}

SkCanvas* SkCanvas_MakeRasterDirect(IMAGE_PARAMETERS) {
    return SkCanvas::MakeRasterDirect(
        SkImageInfo::Make(w, h, static_cast<SkColorType>(colorType), static_cast<SkAlphaType>(alphaType), sk_ref_sp(colorSpace)),
        pixels, rowBytes
    ).release();
}

SkCanvas* SkSVGCanvas_Make(SkDynamicMemoryWStream* stream, float x, float y, float w, float h) {
    return SkSVGCanvas::Make(SkRect::MakeXYWH(x, y, w, h), stream, SkSVGCanvas::kConvertTextToPaths_Flag | SkSVGCanvas::kNoPrettyXML_Flag).release();
}

void SkCanvas_delete(SkCanvas* canvas) {
    delete canvas;
}

SkDocument* SkPDF_MakeDocument(SkDynamicMemoryWStream* stream) {
    return SkPDF::MakeDocument(stream, SkPDF::Metadata()).release();
}

SkCanvas* SkDocument_beginPage(SkDocument* document, float w, float h) {
    return document->beginPage(w, h);
}

void SkDocument_endPage(SkDocument* document) {
    return document->endPage();
}

void SkCanvas_save(SkCanvas* canvas) {
    canvas->save();
}

void SkCanvas_saveLayer(SkCanvas* canvas, bool passBounds, float x, float y, float w, float h, SkPaint* paint) {
    SkRect bounds = SkRect::MakeXYWH(x, y, w, h);
    canvas->saveLayer(passBounds ? &bounds : nullptr, paint);
}

void SkCanvas_restore(SkCanvas* canvas) {
    canvas->restore();
}

void SkCanvas_setMatrix(SkCanvas* canvas, MATRIX23_PARAMETERS) {
    canvas->setMatrix(SkMatrix::MakeAll(m00, m01, m02, m10, m11, m12, 0, 0, 1));
}

void SkCanvas_clipRect(SkCanvas* canvas, float x, float y, float w, float h, bool doAntiAlias) {
    canvas->clipRect(SkRect::MakeXYWH(x, y, w, h), doAntiAlias);
}

void SkCanvas_clipPath(SkCanvas* canvas, Path* path, bool doAntiAlias) {
    canvas->clipPath(convertPath(path), doAntiAlias);
}

void SkCanvas_drawPath(SkCanvas* canvas, Path* path, SkPaint* paint) {
    canvas->drawPath(convertPath(path), *paint);
}

void SkCanvas_drawImage(SkCanvas* canvas, IMAGE_PARAMETERS, float x, float y, unsigned char filterMode, SkPaint* paint) {
    sk_sp<SkImage> image = SkImages::RasterFromPixmap(SkPixmap(
        SkImageInfo::Make(w, h, static_cast<SkColorType>(colorType), static_cast<SkAlphaType>(alphaType), sk_ref_sp(colorSpace)),
        pixels, rowBytes
    ), nullptr, nullptr);
    canvas->drawImage(image, x, y, SkSamplingOptions(static_cast<SkFilterMode>(filterMode)), paint);
}
