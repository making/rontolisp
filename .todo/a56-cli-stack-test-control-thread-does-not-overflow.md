# CliStackTest's control recursion does not always overflow a 1 MiB thread

Difficulty: Low

`CliStackTest.aTestMethodRunsOnTheCliStack` failed once in a full `./mvnw test` (2026-09-27,
x86-64 Linux, Java 25) at line 32: the CONTROL thread (1 MiB stack, `depth(80_000)`) returned
instead of throwing `StackOverflowError`, so `control[0]` was null. The class passes alone.

The control's premise is that 80,000 frames overflow 1 MiB "even with compiled frames"; once the
JIT has compiled (and possibly inlined) `depth` during the rest of the suite, a frame can be small
enough that it does not. Goal: a control that overflows regardless of JIT state (a depth derived
from the stack size with margin, or a frame the JIT cannot shrink), keeping the pairing with the
CLI-stack half.
