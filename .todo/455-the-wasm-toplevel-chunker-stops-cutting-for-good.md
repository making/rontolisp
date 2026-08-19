# 455. The WASM top-level chunker stops cutting for good after one pinning form

Difficulty: High

**`ci-spec.yaml` is at 196 KB of the 256 KiB bound today and grows with every
case added** -- roughly 100 cases of headroom before `CiSpecE2eTest` refuses to
run either WASM backend. This is the cause.

Found 2026-08-19 while spiking rove as a test language (the migration was
cancelled; this finding is independent of it).

## The bug

`codegen/wasm/WasmToplevelEmit.emit` cuts the top level into ~48 KB chunk
functions so no single body gets large. The cut is gated on `pinnedByLocals`:

```java
if (chunk.ctx.locals.size() > localsBefore) {
    pinnedByLocals = true;
}
if (!pinnedByLocals && chunk.body.size() >= CHUNK_TARGET_BYTES) {
    closeChunk(chunk, start, guarded);
    chunk = null;
}
```

`pinnedByLocals` is **never reset**. It is a latch: the first top-level form
that allocates a named local disables chunking for the entire remainder of the
program. The comment above it says "this should never trip; if it does, stop
cutting rather than outline a reader away from its variable... A program that
trips it merely compiles as one chunk, the way the whole top level used to" --
that fallback is exactly the OOM the chunker exists to prevent, and it does
trip, on our own corpus.

## The measurement

Largest emitted function body via `WasmModuleInspector.largestFunctionBodySize`.

**`ci-spec.yaml`'s own program**, first `n` cases concatenated:

| n cases | 100 | 200 | 300 | 390 (all) |
| --- | --- | --- | --- | --- |
| largest body | 51,794 B | 71,651 B | 129,297 B | **196,187 B** |

Not a plateau at one chunk's worth -- a straight line. `CiSpecE2eTest.MAX_WASM_FUNCTION_BODY_BYTES`
is 256 KiB (past it, a wasmtime cold compile OOM-kills the CI runner: 850 KB of
body peaks at 25.8 GB), so the corpus crosses it at roughly 490 cases.

**Synthetic control** -- a program that loads a library, then `n` top-level
forms that each allocate a local (`deftest`):

| n | 3 | 100 | 400 | 700 |
| --- | --- | --- | --- | --- |
| largest body | 159,705 B | 200,321 B | 326,321 B | 452,323 B |

158 KB base + 0.42 KB per form, dead linear -- i.e. no cut at all after the
first one.

**Isolating the latch from the library load**: `(asdf:load-system :rove)`
followed by 2,000 plain `(print ...)` forms gives 157,913 B -- the 2,000 prints
add nothing, because they allocate no locals and the chunker keeps cutting. And
2,000 prints with no library load at all: 49,173 B, exactly one chunk's worth.
The chunker works right up until it is latched.

Second, separate question the numbers raise: that 158 KB **base** is a single
top-level form out of the inlined library source, already 62% of the budget
before the program starts. The chunker cannot cut WITHIN a form, so identify
which form that is and whether the shape is worth splitting.

## What the fix has to preserve

The latch is protecting a real invariant: a named local allocated in chunk A
cannot be read from chunk B. Candidates, cheapest first:

1. **Pin the CURRENT chunk only, not all later ones.** Wrong as written -- a
   later form can read a local an earlier form allocated -- so it needs the read
   side checked: cut only where no local allocated so far is read again.
2. **Back a top-level named local with a module global**, the way
   `GlobalVarCollector` already does for top-level assignments (the comment says
   that is precisely why the top level "should never" allocate one). Find what
   escapes that collector; this may be the real bug and the narrowest fix.
3. **Hoist the locals into the shared `_start` frame** and pass them to chunks.
   Most invasive.

`WasmToplevelChunkingTest` pins the bound, and the new pin must be a program
that TRIPS the latch (a library load plus a long top level, or the ci-spec
corpus itself) -- the existing test passes today.

Measure BOTH WASM builds: `--component` cuts its bodies differently (async
entry+resume pair), and `CiSpecE2eTest`'s `WasmGuard` is split in two precisely
because a 650 KB component body once got through while the core build's largest
was 214 KB.

## Why it matters beyond ci-spec

Every `examples/` program that loads a system and then has a long top level is
on the same curve, silently -- it just has not crossed 256 KiB yet. The size
report tracks the MODULE, never the body, so nothing would show it coming.
