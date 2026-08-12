# `stringp` renders the whole string to answer a predicate, so it is O(n) on wasm

Difficulty: Medium

`(stringp s)` where `s` is a MUTABLE character vector (anything from
`make-string`, or a general array whose elements are characters) costs time
LINEAR in the length of `s`, on the wasm-GC backends only, and pays it on every
call. Found while measuring what the async spelling costs a reactor body drain
-- `(rontolisp:await (rontolisp:read-all s))` over an already-buffered 4096
character body measured 4079 ns against 4.2 ns for reading it directly, and the
whole difference turned out to be the `(stringp s)` in `read-all`'s fast path.

## Measured (2026-08-13, node 24 / V8, wasm-GC `--no-wasi`, 200,000 calls)

| `s` | wasm | interpreter | JVM |
| --- | --- | --- | --- |
| literal, 64 chars | 1 ms | 92 ms | 9 ms |
| `(make-string 64)` | 16 ms | 69 ms | 5 ms |
| `(make-string 1024)` | 215 ms | 54 ms | 2 ms |
| `(make-string 8192)` | **1634 ms** | 56 ms | 0 ms |
| `(copy-seq (make-string 8192))` | **1 ms** | 61 ms | 0 ms |

Exactly linear, ~1 ns per character per call. The interpreter and the JVM are
flat in the length, so this is a wasm-only defect, not a language-level one. The
`copy-seq` row is the tell: the copy is an ordinary `TYPE_STRING`, so the cost
is not "this string is long" but "this value is still the mutable
representation". A `make-string` value stays that way for its whole life.

Sharp detail worth keeping: `(length s)` on the SAME value is O(1) (4.2 ns). It
reads the array header. Only the type predicate walks.

## Why

`WasmStringpCompiler` answers the `TYPE_CELL` arm by calling
`_charvec_to_str` (`WasmStringRuntimeBuilder.buildCharvecToStrBody`) and
`ref.test`ing the result. That function first decides SHAPE with a handful of
O(1) `ref.test`s ending at the marker -- `meta.cdr.cdr` is the i31 `1` -- and
only then renders: it walks every element, `ref.cast`s it to `TYPE_CHAR`, emits
1-4 UTF-8 bytes into scratch grown to `n * 4 + 2`, and finalizes a fresh string
through `_str_fresh`. `stringp` then throws that string away and keeps one bit.

So the predicate already has an O(1) answer available inside the function it
calls; it just runs past it. The class javadoc is right that the `TYPE_CELL`
arm is the only one paying a call (that narrowing was itself a PBKDF2 profile
fix) -- what it does not say is that the call it makes is linear and allocating.

## The fix

Ask the marker, do not build the string. Split the shape decision out of
`_charvec_to_str` so both callers share one copy of the invariant, and have
`stringp` call only that half. Two shapes, and the choice deserves a
measurement rather than a preference:

- a new `_charvec_p` runtime function -- one owner of the marker logic, but a
  new `FUNC_*` index, so the fixed-index invariant in `.kb/wasm-import.md`
  applies (every constant after it shifts);
- inline the six `ref.test`s at the `stringp` site -- no index shift, no call,
  but the marker invariant is then spelled in two places, which is exactly the
  kind of duplication that rots.

Gate: the table above re-measured (the 8192 row must join the `copy-seq` row at
~1 ms), plus byte-identity for a module that never reaches the `TYPE_CELL` arm.

## Second, riskier idea from the same measurement: normalization is never memoized

The predicate is the clear bug, but every OTHER caller of `_charvec_to_str`
re-renders too, because the result is never written back into the cell --
`string=`, `string-upcase`, `subseq`, `concatenate`, `string-trim`,
`write-string`, `char`, `read-from-string` all normalize their mutable
arguments on every single call. A loop comparing two `make-string` buffers
renders both, every iteration.

Caching the rendered string in the cell would fix that class outright, and is
deliberately NOT part of the fix above: a character vector is MUTABLE, so
`(setf (aref v i) c)`, `replace`, `fill` and a fill-pointer move would all have
to invalidate the cache, and missing one is silent wrong output rather than
slow output. Worth its own measurement of how much the re-rendering actually
costs a real program (jzon and the ironclad slice are the string-heavy ones)
before deciding whether that invalidation surface is worth buying.

## Where it bites today

`rontolisp:read-all`'s `(if (stringp s) s ...)` fast path -- the one spelling
every backend shares for draining a body -- is O(body) on wasm whenever the body
arrived as a mutable buffer. `.todo/341` Phase 2 routes a reactor request body
through exactly that path, so it wants this fixed first or it will measure the
wrong thing.
