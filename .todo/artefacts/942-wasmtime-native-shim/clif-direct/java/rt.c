#include <stdio.h>
#include <stdint.h>
int64_t rl_main(void);
int64_t rl_print(int64_t x) { printf("%lld\n", (long long) x); return x; }
int main(void) { rl_main(); return 0; }
