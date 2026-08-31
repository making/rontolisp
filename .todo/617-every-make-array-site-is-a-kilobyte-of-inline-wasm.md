# Every `make-array` site is up to a kilobyte of INLINE wasm

Difficulty: Medium

Measured while landing `.todo/612`. `WasmArrayCompiler.compileMake` emits the
whole allocation inline at every call site -- dimension parse, total-size fold,
`array.new`, the fill-pointer resolution, the meta marker -- with no runtime
helper function anywhere. Numbers taken 2026-08-31 at `--optimize=size`, raw
wasm bytes, by varying only the number of `make-array` sites in a program:

| shape | bytes per site |
| --- | --- |
| general, no keywords | ~400-600 |
| general, with `:fill-pointer` / `:adjustable` | ~1,100 |
| packed `(unsigned-byte n)` / float | ~500 |

That is what made todo-612's seven-arm dispatch unaffordable inline (+32.6% on
array-operations for three call sites) and pushed it into a prelude helper. The
helper fixed the arms; it did not fix the underlying cost, which every program
with many `make-array` sites still pays -- and the shipped Lisp has plenty:
`linalg.lisp`, `vec.lisp`, `geom.lisp` and `scene.lisp` allocate at nearly every
constructor.

The JVM backend does NOT have this shape: it calls `_arrayMake` /
`_arrayMakeTyped` / `_ivMake` / `_fvMake`, so a site there is an
`invokestatic` and the body is emitted once (`JvmArrayRuntimeBuilder`). The
wasm backend should be able to do the same -- a `$_array_make(dims, init, fp,
adj, code)` function in a `WasmArrayRuntimeBuilder`, gated on the program using
arrays at all, with the packed kinds as separate entries the way the JVM keeps
`_ivMake` separate.

Two things to establish before committing to it:

1. **How much it actually saves.** Count the `make-array` sites that survive
   the shaker in a few real programs (`hello-tiny-routes` full, the linalg
   examples, `httpbin-clack`) and multiply. A program with three sites gains
   nothing and pays for the function; the win is in the array-heavy ones.
2. **Whether the inline form is buying speed.** The inline emission keeps the
   dimensions on the stack and the whole thing is straight-line; a call has to
   box its arguments the way the JVM's does. `bench-report/programs/matmul.lisp`
   and the linalg suite are the rows that would move. If allocation is hot
   there, the answer may be a helper for the COLD shapes only (`:fill-pointer` /
   `:adjustable`, the 1,100-byte one) and inline for the rest, which is most of
   the saving for none of the risk.

If the measurement says the saving is small, the finding IS the deliverable:
write the per-site numbers into `.kb/array-literals.md` beside todo-612's table
and close the item.
