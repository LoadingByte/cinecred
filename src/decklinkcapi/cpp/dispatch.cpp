#if defined(_WIN32)
#include "DeckLinkAPI_i.c"
#elif defined(__APPLE_CC__)
#include "sdk/mac/DeckLinkAPIDispatch.cpp"
#elif defined(__linux__)
#include "sdk/linux/DeckLinkAPIDispatch.cpp"
#endif
