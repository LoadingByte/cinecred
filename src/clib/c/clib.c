#if defined(_M_X64)
#include <intrin.h>
#elif defined(__x86_64__)
#include <cpuid.h>
#endif

#include "clib.h"

CAPI void cpuid(unsigned int leaf, unsigned int subleaf, unsigned int* registers) {
#if defined(_M_X64)
    __cpuidex((int*) registers, leaf, subleaf);
#elif defined(__x86_64__)
    __get_cpuid_count(leaf, subleaf, registers + 0, registers + 1, registers + 2, registers + 3);
#endif
}
