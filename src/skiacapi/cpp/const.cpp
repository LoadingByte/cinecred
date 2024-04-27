#include "include/core/SkAlphaType.h"
#include "include/core/SkBlendMode.h"
#include "include/core/SkColorType.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPathTypes.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkTileMode.h"
#include "include/effects/SkGradientShader.h"
#include "skiacapi.h"

unsigned char SkColorType_RGBA_F32(void) { return static_cast<unsigned char>(SkColorType::kRGBA_F32_SkColorType); }
unsigned char SkColorType_A16_unorm(void) { return static_cast<unsigned char>(SkColorType::kA16_unorm_SkColorType); }

unsigned char SkAlphaType_Opaque(void) { return static_cast<unsigned char>(SkAlphaType::kOpaque_SkAlphaType); }
unsigned char SkAlphaType_Premul(void) { return static_cast<unsigned char>(SkAlphaType::kPremul_SkAlphaType); }
unsigned char SkAlphaType_Unpremul(void) { return static_cast<unsigned char>(SkAlphaType::kUnpremul_SkAlphaType); }

unsigned char SkTileMode_Clamp(void) { return static_cast<unsigned char>(SkTileMode::kClamp); }
unsigned char SkTileMode_Decal(void) { return static_cast<unsigned char>(SkTileMode::kDecal); }

unsigned char SkFilterMode_Nearest(void) { return static_cast<unsigned char>(SkFilterMode::kNearest); }
unsigned char SkFilterMode_Linear(void) { return static_cast<unsigned char>(SkFilterMode::kLinear); }

unsigned char SkPathVerb_Move(void) { return static_cast<unsigned char>(SkPathVerb::kMove); }
unsigned char SkPathVerb_Line(void) { return static_cast<unsigned char>(SkPathVerb::kLine); }
unsigned char SkPathVerb_Quad(void) { return static_cast<unsigned char>(SkPathVerb::kQuad); }
unsigned char SkPathVerb_Cubic(void) { return static_cast<unsigned char>(SkPathVerb::kCubic); }
unsigned char SkPathVerb_Close(void) { return static_cast<unsigned char>(SkPathVerb::kClose); }

unsigned char SkPaintCap_Butt(void) { return static_cast<unsigned char>(SkPaint::Cap::kButt_Cap); }
unsigned char SkPaintCap_Round(void) { return static_cast<unsigned char>(SkPaint::Cap::kRound_Cap); }
unsigned char SkPaintCap_Square(void) { return static_cast<unsigned char>(SkPaint::Cap::kSquare_Cap); }

unsigned char SkPaintJoin_Miter(void) { return static_cast<unsigned char>(SkPaint::Join::kMiter_Join); }
unsigned char SkPaintJoin_Round(void) { return static_cast<unsigned char>(SkPaint::Join::kRound_Join); }
unsigned char SkPaintJoin_Bevel(void) { return static_cast<unsigned char>(SkPaint::Join::kBevel_Join); }

unsigned char SkBlendMode_Clear(void) { return static_cast<unsigned char>(SkBlendMode::kClear); }
unsigned char SkBlendMode_Src(void) { return static_cast<unsigned char>(SkBlendMode::kSrc); }
unsigned char SkBlendMode_Dst(void) { return static_cast<unsigned char>(SkBlendMode::kDst); }
unsigned char SkBlendMode_SrcOver(void) { return static_cast<unsigned char>(SkBlendMode::kSrcOver); }
unsigned char SkBlendMode_DstOver(void) { return static_cast<unsigned char>(SkBlendMode::kDstOver); }
unsigned char SkBlendMode_SrcIn(void) { return static_cast<unsigned char>(SkBlendMode::kSrcIn); }
unsigned char SkBlendMode_DstIn(void) { return static_cast<unsigned char>(SkBlendMode::kDstIn); }
unsigned char SkBlendMode_SrcOut(void) { return static_cast<unsigned char>(SkBlendMode::kSrcOut); }
unsigned char SkBlendMode_DstOut(void) { return static_cast<unsigned char>(SkBlendMode::kDstOut); }
unsigned char SkBlendMode_SrcATop(void) { return static_cast<unsigned char>(SkBlendMode::kSrcATop); }
unsigned char SkBlendMode_DstATop(void) { return static_cast<unsigned char>(SkBlendMode::kDstATop); }
unsigned char SkBlendMode_Xor(void) { return static_cast<unsigned char>(SkBlendMode::kXor); }
unsigned char SkBlendMode_Plus(void) { return static_cast<unsigned char>(SkBlendMode::kPlus); }
unsigned char SkBlendMode_Modulate(void) { return static_cast<unsigned char>(SkBlendMode::kModulate); }
unsigned char SkBlendMode_Screen(void) { return static_cast<unsigned char>(SkBlendMode::kScreen); }
unsigned char SkBlendMode_Overlay(void) { return static_cast<unsigned char>(SkBlendMode::kOverlay); }
unsigned char SkBlendMode_Darken(void) { return static_cast<unsigned char>(SkBlendMode::kDarken); }
unsigned char SkBlendMode_Lighten(void) { return static_cast<unsigned char>(SkBlendMode::kLighten); }
unsigned char SkBlendMode_ColorDodge(void) { return static_cast<unsigned char>(SkBlendMode::kColorDodge); }
unsigned char SkBlendMode_ColorBurn(void) { return static_cast<unsigned char>(SkBlendMode::kColorBurn); }
unsigned char SkBlendMode_HardLight(void) { return static_cast<unsigned char>(SkBlendMode::kHardLight); }
unsigned char SkBlendMode_SoftLight(void) { return static_cast<unsigned char>(SkBlendMode::kSoftLight); }
unsigned char SkBlendMode_Difference(void) { return static_cast<unsigned char>(SkBlendMode::kDifference); }
unsigned char SkBlendMode_Exclusion(void) { return static_cast<unsigned char>(SkBlendMode::kExclusion); }
unsigned char SkBlendMode_Multiply(void) { return static_cast<unsigned char>(SkBlendMode::kMultiply); }
unsigned char SkBlendMode_Hue(void) { return static_cast<unsigned char>(SkBlendMode::kHue); }
unsigned char SkBlendMode_Saturation(void) { return static_cast<unsigned char>(SkBlendMode::kSaturation); }
unsigned char SkBlendMode_Color(void) { return static_cast<unsigned char>(SkBlendMode::kColor); }
unsigned char SkBlendMode_Luminosity(void) { return static_cast<unsigned char>(SkBlendMode::kLuminosity); }

unsigned char SkGradientShaderInterpolationColorSpace_Destination(void) { return static_cast<unsigned char>(SkGradientShader::Interpolation::ColorSpace::kDestination); }
unsigned char SkGradientShaderInterpolationColorSpace_OKLab(void) { return static_cast<unsigned char>(SkGradientShader::Interpolation::ColorSpace::kOKLab); }
unsigned char SkGradientShaderInterpolationColorSpace_SRGB(void) { return static_cast<unsigned char>(SkGradientShader::Interpolation::ColorSpace::kSRGB); }

const float* SkNamedTransferFn_SRGB(void) { return reinterpret_cast<const float*>(&SkNamedTransferFn::kSRGB); }
const float* SkNamedTransferFn_Rec2020(void) { return reinterpret_cast<const float*>(&SkNamedTransferFn::kRec2020); }
const float* SkNamedTransferFn_PQ(void) { return reinterpret_cast<const float*>(&SkNamedTransferFn::kPQ); }
const float* SkNamedTransferFn_HLG(void) { return reinterpret_cast<const float*>(&SkNamedTransferFn::kHLG); }

const float* SkNamedGamut_SRGB(void) { return reinterpret_cast<const float*>(&SkNamedGamut::kSRGB); }
const float* SkNamedGamut_AdobeRGB(void) { return reinterpret_cast<const float*>(&SkNamedGamut::kAdobeRGB); }
const float* SkNamedGamut_DisplayP3(void) { return reinterpret_cast<const float*>(&SkNamedGamut::kDisplayP3); }
const float* SkNamedGamut_Rec2020(void) { return reinterpret_cast<const float*>(&SkNamedGamut::kRec2020); }
