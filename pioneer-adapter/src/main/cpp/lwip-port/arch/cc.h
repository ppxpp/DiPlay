#ifndef DIPLAY_LWIP_CC_H
#define DIPLAY_LWIP_CC_H
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <time.h>
#define LWIP_TIMEVAL_PRIVATE 0
#define LWIP_DONT_PROVIDE_BYTEORDER_FUNCTIONS 1
#define LWIP_PLATFORM_DIAG(x) do { printf x; } while(0)
void diplay_assert(const char *message, const char *file, int line);
#define LWIP_PLATFORM_ASSERT(x) diplay_assert(x, __FILE__, __LINE__)
uint32_t diplay_random(void);
#endif
