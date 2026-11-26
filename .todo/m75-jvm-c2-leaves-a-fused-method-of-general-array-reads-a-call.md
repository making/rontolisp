# m75. JVM: C2 leaves a fused method of general-array reads a call

Difficulty: High

C2 does not inline a callee it has already compiled to more than `InlineSmallCode` (2,500 B of
machine code), and a fused method is compiled before its caller. SHA-512's round (298
bytecodes since its fallback is a method of its own) is 2,840-2,992 B; a general array's read
alone is 928-944 (`_fxLongs`, 608 B, inlined at every read), the schedule's store with three
of them 4,272-4,384 (`.kb/jvm-int-fusion.md`, "What C2 still leaves", 2026-11-26). Measured the
same day: SHA-512 of 4 MiB 107-110 ms on C2, 93-95 when the block's compile wins the race, 83
with `-XX:InlineSmallCode=6000` -- Graal's 83-85.

- Price first what a read costs with its storage resolved once: the `long[]` of a pinned array
  its loop does not rebind, handed to the fused methods as an argument -- the round's and the
  store's machine code sizes (`-XX:+LogCompilation`) and both JITs' times
  (`.kb/measurement-probes.md`, rule 2).
- The block's load (`_fx$N`, 718 bytecodes of fast path) is past `FreqInlineSize` whatever its
  reads cost.
