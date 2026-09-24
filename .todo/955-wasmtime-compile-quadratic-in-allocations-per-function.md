# 955. wasmtime compile time is quadratic in the GC allocations of one function

Difficulty: Medium

Found by `.todo/953`, which took the operand-stack depth out of long quoted lists
(`.kb/quoted-data.md`, "A long list is built in runs"). What is left does not depend on the
stack: `wasmtime compile` time grows with the square of the `struct.new`s in ONE function.
Hand-written, wasmtime 49.0.0, N x `ref.i31; ref.null; struct.new; drop`: 1.4 / 4.5 / 18.8 s
at 5,000 / 10,000 / 20,000. At 10,000, `-C collector=null` takes 1.2 s and
`-O regalloc-algorithm=single-pass` 1.35 s, so it is the DRC allocation path under the
backtracking allocator. The same 20,000 cells split into helper functions of 64 compile in
0.2 s. A quoted list of 50,000 symbols still takes 94 s. Generator and table:
`.todo/artefacts/953-wasm-compile-quadratic-in-list-length/`.

## Plan

- Reduce the module to a rontolisp-free wat (the `drop` shape is one) and report it against
  `bytecodealliance/wasmtime`, as `.todo/731` does for the landing-pad bug.
- Measure whether real programs reach this: the largest allocation count per function across
  `examples/` and the Worker family. A large quoted list is the one shape known to.
- If one does: build a quoted list longer than some bound in helper functions, one per run,
  each taking the tail and returning the list. The wasm-GC backend has to append functions
  after the bodies compile, and `WasmInliner` must not inline a helper back into its one
  caller. Measure the size cost against `size-report` first.
