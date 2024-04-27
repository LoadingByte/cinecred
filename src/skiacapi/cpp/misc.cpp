#include "include/core/SkColorSpace.h"
#include "include/core/SkPixmap.h"
#include "include/core/SkRefCnt.h"
#include "include/core/SkStream.h"
#include "include/encode/SkICC.h"
#include "skiacapi.h"

void SkRefCnt_unref(SkRefCnt* object) {
    object->unref();
}

void SkData_unref(SkData* data) {
    data->unref();
}

long long SkData_size(SkData* data) {
    return data->size();
}

const void* SkData_data(SkData* data) {
    return data->data();
}

SkDynamicMemoryWStream* SkDynamicMemoryWStream_New(void) {
    return new SkDynamicMemoryWStream;
}

void SkDynamicMemoryWStream_delete(SkDynamicMemoryWStream* stream) {
    delete stream;
}

SkData* SkDynamicMemoryWStream_detachAsData(SkDynamicMemoryWStream* stream) {
    return stream->detachAsData().release();
}

SkColorSpace* SkColorSpace_MakeRGB(TRC_PARAMETERS, MATRIX33_PARAMETERS) {
    skcms_TransferFunction fn = { g, a, b, c, d, e, f };
    skcms_Matrix3x3 toXYZD50 = {{{ m00, m01, m02 }, { m10, m11, m12 }, { m20, m21, m22 }}};
    return SkColorSpace::MakeRGB(fn, toXYZD50).release();
}

SkData* SkICC_SkWriteICCProfile(TRC_PARAMETERS, MATRIX33_PARAMETERS) {
    skcms_TransferFunction fn = { g, a, b, c, d, e, f };
    skcms_Matrix3x3 toXYZD50 = {{{ m00, m01, m02 }, { m10, m11, m12 }, { m20, m21, m22 }}};
    return SkWriteICCProfile(fn, toXYZD50).release();
}
