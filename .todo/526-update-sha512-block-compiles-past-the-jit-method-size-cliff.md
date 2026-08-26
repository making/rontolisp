# 526. ironclad's update-sha512-block compiles past HotSpot's 8000-byte JIT cliff

Difficulty: High (the fix is method splitting of an emitted defun body -- a new
codegen capability, not a tuning knob)

Found while closing `.todo/412`, which added `JvmLibraryMethodSizeTest` (every
defun/lambda method of the ironclad-loading compile must stay under HotSpot's
8000-bytecode `HugeMethodLimit`, `.kb/hot-path-method-size.md`). One method
fails and is excluded BY NAME in that test: `UPDATE-SHA512-BLOCK`, ironclad's
fully unrolled 80-round SHA-512 compression.

This is a PRE-EXISTING condition, not a fusion regression: before integer
fusion it compiled to 15,043 bytecodes (already 1.9x the limit -- it has been
running in the bytecode interpreter all along, unreported); with fusion it is
18,043 (root-position calls to its inlinable helpers now fuse, and each fused
call site's leaf pushes outweigh a bare call's argument pushes in this
80-round unrolled body). The interpreted BODY now calls JIT-compiled `_fx$N`
round methods, so the growth is not a slowdown -- but every SHA-384/SHA-512
digest on the JVM backend still pays the interpreter tax for the glue between
rounds.

## What to build

The body has no Lisp-level split point (it is one `let*` chain of 80 rounds),
so the compiler has to split it: outline runs of top-level body forms into
synthetic `(...)Ljava/lang/Object;` continuation methods passing the live
locals, or widen the fused-site outlining (`.kb/jvm-int-fusion.md`) so a round's
WHOLE `let*` step -- not just each value tree -- becomes one shared method.
`update-sha256-block` (64 rounds, 5,339 bytes) shows the margin: SHA-512's
rounds are the same shape at 64-bit width, so a per-round outlining that shares
the shape across rounds should land well under the limit.

## Acceptance

- The named exclusion in `JvmLibraryMethodSizeTest` is deleted and the test
  passes.
- `IroncladE2eTest`'s SHA-384/SHA-512 vectors stay byte-identical on all four
  backends.
- A SHA-512 digest benchmark on `-o Prog.class` improves by roughly the JIT
  factor (the sha256 analogue measured 1.7x for being excluded from JIT).
