# m60. JVM: C2 inlines no fused method past 325 bytecodes

Difficulty: High

HotSpot's C2 inlines a hot method of at most `FreqInlineSize` (325) bytecodes; a fused method
(`JvmIntFusionCompiler`, `_fx$N`) carries its tree twice, the fallback being most of it, so a
tree of a few operations is past the limit and stays a call: SHA-512's round is 877 B
(`-XX:+PrintInlining`, 2026-11-26), the message schedule's store 415 B. Graal inlines both.
Measured the same day: SHA-512 of 4 MiB steadies at 91-106 ms on Graal and 138-161 ms on C2.

- Price the ceiling first: the same program with each fused method's fallback moved to a method
  of its own the fast path calls on a bail (`.kb/measurement-probes.md`, rule 2), on both JITs,
  and the classes' sizes (`.kb/jvm-int-fusion.md`, "Outlined, not inline").
