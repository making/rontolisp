# 413. On wasm-GC a real library's hot loop pays for dispatch and `&key` parsing, not for arithmetic

Difficulty: High (two independent seams in the emitter -- the fused/boxed
boundary and the call ABI -- each with a measured share and no existing
mechanism to extend)

Measured while closing `.todo/253` item 1 (the native interpreter PBKDF2). The
question was "the compiled backends are still slower than the native kernel --
where does it go", and on wasm-GC the answer is NOT the integer arithmetic. The
todo-194 fusion machinery works; what surrounds it does not.

## The measurement (2026-08-16, M4, wasmtime 47.0.3)

Real ironclad v0.61, `pbkdf2-derive-key :sha256` at 4096 iterations (= 16,384
SHA-256 compressions), `--component`, steady state over 40 repetitions:

| | per derivation | per compression |
| --- | --- | --- |
| interpreter, native kernel (`eval/Sha2Kernels`) | 9 ms | 0.5 us |
| **wasm component** | **137 ms** | 8.4 us |
| JVM `.class` | 44 ms | 2.7 us |

A faithful STANDALONE reproduction of ironclad's compression -- the same
`mod32+`/`rol32` defuns, the same `ch`/`maj`/`sigma0`/`sigma1` flets, 64
straight-line rounds over two packed `(unsigned-byte 32)` arrays -- runs at
**1.5 us per block** on the same wasm component. So the round arithmetic is
already 5.6x faster than what the real library gets, and the emitter is not the
bottleneck the shape suggests.

`wasmtime run --profile guest` over the Preview 1 build (note: the profiler
reports the DEFINED-function index, so its `<wasm function N>` is
`wasm-tools print`'s `(;N + <import count>;)` -- 9 imports here; getting that
offset wrong sends you chasing the wrong functions for an hour):

| inclusive | what |
| --- | --- |
| 69% | the indirect-call dispatcher |
| 55% / 45% | two ironclad functions whose bodies open with a cons-walking loop -- runtime `&key` plist parsing |
| 39% | the symbol -> function-id lookup behind a dispatched call |
| **33%** | `update-sha256-block` (the whole compression) |

And WITHIN the compression, by self time:

| self | what |
| --- | --- |
| 32% | the raw-local resolver: re-BOXING an unboxed dual-representation local for a boxed consumer |
| 12% | `_int_new` (boxing an i64) |
| 18% + 6% | `_fx_add` / `_fx_sub`, the CHECKED helpers -- the index arithmetic (`(- i 15)`, loop counters) that no literal `logand` mask covers |

So even the fused third of the profile spends ~44% of itself crossing the
fused/boxed boundary. The emitted `update-sha256-block` does contain 3,296
inline i64 ops, so fusion FIRED -- the cost is at its edges.

## The two seams

### 1. The call ABI: `&key` parsing and dispatched calls

ironclad's digest protocol is `(update-digest d seq :start s :end e)`,
`(produce-digest d :digest buf :digest-start k)`, `(hmac-digest h :buffer out)`
-- every call in the inner loop walks a keyword plist at run time, and reaches
its callee through the dispatcher rather than a direct `call`. Two candidate
attacks, both general (every library that takes keywords pays this):

- **Compile-time keyword resolution.** A call whose keyword arguments are all
  LITERAL keywords, to a known lambda list, can bind straight to positional
  slots at compile time -- no plist, no loop. This is the same class of move as
  `.kb/pure-builtin-fold.md`: decide at compile time what the shape makes
  decidable, and leave the general path for everything else.
- **Direct calls for a known callee.** The 69%-inclusive dispatcher suggests
  calls that could be a direct `call` are going through `call_indirect` + a
  symbol lookup. Find out which ones, and why the direct path declines.

### 2. The fused/boxed boundary

- A value produced by a fused tree and consumed by a boxed context boxes; a raw
  local read in a boxed context resolves through the 32%-self-time helper. The
  win is to widen what stays raw -- across a `let` binding, into a call
  argument, into a packed-array store -- rather than to make boxing cheaper.
- The checked `_fx_add`/`_fx_sub` share is index arithmetic. Nothing masks it,
  so the masked-wrap peephole cannot fire; a value RANGE (an index known to be
  a small non-negative integer, e.g. a `dotimes` counter or `(- i 15)` under an
  `aref` bound) would let it emit unchecked. That is a small type-inference
  step, not a general one.

## What NOT to conclude

- Do not port a native SHA-256 into the emitter. The arithmetic is not the
  problem here (1.5 us/block standalone), and a per-library kernel does not
  generalize.
- Do not assume the JVM has the same profile. It does not -- there the
  arithmetic IS the cost and the dispatch is not (`.todo/412`). The two
  backends are off in opposite directions, which is why each needs its own
  measurement before its own fix.

## Acceptance

- The ironclad PBKDF2 derivation on `--component` moves materially toward the
  1.5 us/block the standalone reproduction already achieves, and the new number
  replaces the one recorded in `.kb/asdf.md`.
- Whatever lands is pinned by a benchmark-shaped test or a `.kb` note that
  states the measured share it addresses, so the next visitor can tell whether
  the reason still holds.
- `IroncladE2eTest` + `ClPostgresE2eTest` stay byte-identical on all four
  backends -- this is a codegen change, not a semantics change.
