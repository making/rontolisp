# 412. The JVM backend boxes every integer: it has no equivalent of the wasm expression-tree fusion

Difficulty: High (a new emitter pass on the JVM side; the design is proven --
`WasmIntFusionCompiler` is the reference implementation -- but the JVM's value
model, locals and verifier constraints are its own)

The sibling of `.todo/411`, from the same measurement session. Both compiled
backends are ~15-25x off a native kernel on ironclad's PBKDF2, and they are off
for OPPOSITE reasons: wasm-GC's arithmetic is fused and its dispatch is not,
while the JVM's dispatch is fine (HotSpot inlines the static methods) and its
arithmetic allocates a `java.lang.Long` per intermediate value.

## The measurement (2026-08-16, M4, GraalVM)

Real ironclad, `pbkdf2-derive-key :sha256` x4096, steady state over 40
repetitions: **44 ms** (2.7 us per SHA-256 compression) against 9 ms for the
native kernel. JFR, `settings=profile`, over that run:

- **551 of 600 `ObjectAllocationSample` events are `java.lang.Long`** -- one
  allocation per intermediate integer.
- 4,956 `GCPhaseParallel` events in 2 seconds. The cost is the allocation rate,
  not the arithmetic.
- Top self time: `MOD32+`, `UPDATE-SHA256-BLOCK`, `_invoke_1`/`_invoke_3`,
  `_ivAref1`/`_ivAset1`.

The A/B that isolates it: a hand-written round loop whose values stay in JVM
locals runs at ~0.6 ns/op (escape analysis eliminates the boxes), while the same
shape reading and writing through packed arrays and struct slots runs at
~3.5 ns/op. Boxing at the boundaries is the whole difference, and the JVM has no
pass that keeps a value unboxed ACROSS operations -- `JvmBitwiseCompiler` calls
one runtime helper per operator with an `instanceof Long` fast path
(`.kb/integer-bitwise-fast-paths.md`, already worth 5x over the BigInteger
round trip it replaced), but each helper still takes and returns a boxed value.

## What to build

The JVM analogue of todo-194's `WasmIntFusionCompiler`
(`.kb/wasm-int-fusion.md`, `.kb/wasm-unboxed-locals.md`), which is a working
reference for every decision this needs:

- classify an expression into an integer operation tree over leaves,
- unbox each leaf ONCE into a `long` local, emit the tree as primitive `long`
  ops, and box only the result,
- the masked-wrap peephole (under a literal `logand` mask or power-of-two `mod`,
  `+ - *` and left-`ash` emit unchecked -- the low bits of a wrapped result are
  exact), which is what makes `mod32+`/`rol32`-shaped code pay nothing,
- substitution of closed one-liner integer defuns (`mod32+`, `rol32`) and of
  `flet`-bound local functions, so the tree spans the library's own helpers,
- unboxed dual-representation locals with a boxed shadow, so a `let` variable in
  a hot loop never round-trips,
- a total escape hatch: any leaf that is not an integer at run time bails to the
  ordinary boxed path, which must produce exactly what it produces today.

Two JVM-specific notes the wasm version does not have to answer: the emitted
frames must stay verifiable under the offline `StackMapAugmenter`
(`.kb/stackmap-augmenter.md`), and every fused site emits its tree twice (raw +
fallback), which pushes method bodies toward HotSpot's 8000-bytecode
`HugeMethodLimit` -- see below.

## Bring the method-size guard to third-party code in the same pass

ironclad's `update-sha256-block` compiles to **7,667 bytecodes**, 96% of the
`HugeMethodLimit` that `.kb/hot-path-method-size.md` exists to keep us under.
Excluding it from JIT (`-XX:CompileCommand=exclude`) costs 1.7x on the PBKDF2
benchmark, so the cliff is real and the margin is 4%. The size is set by OUR
emitter over a THIRD-PARTY source, and `LispEvaluatorHotMethodSizeTest` only
watches rontolisp's own methods -- nothing would report the day a codegen change
pushes a library's hot function over. Doubling every fused site's code makes
this urgent rather than theoretical: either the fusion budget accounts for the
enclosing method's size, or the function gets split, or both.

## Acceptance

- The ironclad PBKDF2 derivation on `-o Prog.class` moves materially toward the
  native kernel, and the JFR allocation profile of that run is no longer
  dominated by `java.lang.Long`.
- No emitted method that runs per compression crosses 8000 bytecodes, and a test
  fails if one does -- for LIBRARY code, not only for ours.
- `IroncladE2eTest` + `ClPostgresE2eTest` stay byte-identical on all four
  backends; the fallback path must be exactly today's behavior, including
  overflow promotion to exact arbitrary precision.
