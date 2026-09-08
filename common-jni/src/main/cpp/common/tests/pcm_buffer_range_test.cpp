#include "pcm_buffer_range.h"
#include <cassert>
#include <climits>
#include <cstdio>
int main() {
    using stt::pcmBufferRangeError;
    assert(!pcmBufferRangeError(3200, 0, 3200, 16000));
    assert(!pcmBufferRangeError(3200, 1600, 1600, 16000));
    assert(pcmBufferRangeError(3200, 1, 1600, 16000));
    assert(pcmBufferRangeError(3200, -2, 1600, 16000));
    assert(pcmBufferRangeError(3200, 0, 1, 16000));
    assert(pcmBufferRangeError(3200, 0, 0, 16000));
    assert(pcmBufferRangeError(-1, 0, 1600, 16000));
    assert(pcmBufferRangeError(3200, 0, 3200, 0));
    assert(pcmBufferRangeError(3200, 0, 3200, -1));
    assert(pcmBufferRangeError(3200, 1602, 1600, 16000));
    assert(pcmBufferRangeError(INT_MAX, INT_MAX - 1, INT_MAX - 1, 16000));
    assert(!pcmBufferRangeError(4294967296LL, INT_MAX - 1, INT_MAX - 1, 16000));
    std::puts("pcm_buffer_range: 12 checks PASS");
}
