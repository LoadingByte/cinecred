#include "include/core/SkColorSpace.h"
#include "include/core/SkImage.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRefCnt.h"
#include "include/core/SkStream.h"
#include "modules/svg/include/SkSVGDOM.h"
#include "modules/svg/include/SkSVGSVG.h"
#include "skiacapi.h"

class SingleImageAsset : public skresources::ImageAsset {
public:
    SingleImageAsset(sk_sp<SkImage> image) : fImage(image) {}
    bool isMultiFrame() override { return false; }
    FrameData getFrameData(float t) override {
        return { fImage, SkSamplingOptions(SkFilterMode::kLinear), SkMatrix::I(), SizeFit::kCenter };
    }
private:
    const sk_sp<SkImage> fImage;
};

class UpcallingResourceProvider : public skresources::ResourceProvider {
public:
    UpcallingResourceProvider(loadImage_t loadImage) : fLoadImage(loadImage) {}
    sk_sp<SkData> load(const char[], const char[]) const override { return nullptr; }
    sk_sp<SkTypeface> loadTypeface(const char[], const char[]) const override { return nullptr; }
    sk_sp<SkData> loadFont(const char[], const char[]) const override { return nullptr; }
    sk_sp<skresources::ExternalTrackAsset> loadAudioAsset(const char[], const char[], const char[]) override { return nullptr; }
    sk_sp<skresources::ImageAsset> loadImageAsset(const char path[], const char name[], const char id[]) const override {
        int w, h;
        unsigned char colorType, alphaType;
        SkColorSpace* colorSpace;
        void* pixels;
        long long rowBytes;
        if (!fLoadImage(path, name, id, &w, &h, &colorType, &alphaType, &colorSpace, &pixels, &rowBytes))
            return nullptr;
        sk_sp<SkImage> image = SkImages::RasterFromPixmap(SkPixmap(
            SkImageInfo::Make(w, h, static_cast<SkColorType>(colorType), static_cast<SkAlphaType>(alphaType), sk_ref_sp(colorSpace)),
            pixels, rowBytes
        ), nullptr, nullptr);
        return sk_sp(new SingleImageAsset(image));
    }
private:
    loadImage_t fLoadImage;
};

SkSVGDOM* SkSVGDOM_Make(char* str, long long len, loadImage_t loadImage) {
    return SkSVGDOM::Builder().setResourceProvider(sk_sp(new UpcallingResourceProvider(loadImage))).make(*SkMemoryStream::MakeDirect(str, len)).release();
}

void SkSVGDOM_containerSize(SkSVGDOM* dom, float wh[2]) {
    SkSize size = dom->containerSize();
    wh[0] = size.width();
    wh[1] = size.height();
}

void SkSVGDOM_setContainerSize(SkSVGDOM* dom, float w, float h) {
    dom->setContainerSize(SkSize::Make(w, h));
}

bool SkSVGDOM_getViewBox(SkSVGDOM* dom, float box[4]) {
    SkTLazy<SkRect> lvb = dom->getRoot()->getViewBox();
    if (lvb.isValid()) {
        SkRect vb = *lvb;
        box[0] = vb.x();
        box[1] = vb.y();
        box[2] = vb.width();
        box[3] = vb.height();
        return true;
    }
    return false;
}

void SkSVGDOM_render(SkSVGDOM* dom, SkCanvas* canvas) {
    dom->render(canvas);
}
