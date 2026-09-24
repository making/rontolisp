# WASM compile time grows quadratically with one list's length

Difficulty: Medium

Found while making the passes' stack cost independent of list length (.todo/952,
`.kb/interpreter-stack.md`, "Depth is NESTING"). The stack is fixed now; the TIME is
not. Both are pre-existing (the same numbers on the tree before that change), both on the
wasm-GC target only.

1. **`rontolisp -o x.wasm` itself.** A backquote template of N constants expands to one
   `(LIST x 0 1 ... )` call of N+1 arguments. Measured 2026-09-24, linux-x64, whole CLI
   run: N=2,000 6 s, 4,000 15-18 s, 8,000 72 s; 50,000 did not finish in 10 minutes. JFR:
   `am.ik.wasm.WasmLocalSink.sinkEntry` (called from `sink` <- `WasmLispCompiler.shakeCore`)
   dominates, then `inputsUnchanged` and `encode`. The JVM target refuses the same call
   (65,535-byte method limit), which is a separate, honest limit.
2. **The emitted module under wasmtime.** A quoted list of N symbols
   (`'(s0 s1 ...)`) compiles in rontolisp in seconds, but `wasmtime compile` takes 9.9 s at
   N=5,000, 38 s at 10,000, 161 s at 20,000 (the program then runs in 0.03 s). A quoted
   list of numbers of the same length is fast. `WasmQuoteCompiler.compileQuotedCons` pushes
   every car before the first `struct.new`, so the operand stack is N deep; building the
   list from the tail through a local (constant operand depth) is the likely fix -- measure
   Cranelift's time against the stack depth before choosing.

## Plan

- Find what in `WasmLocalSink.sinkEntry` is linear per instruction per body and make the
  sink linear in body size; pin it with a body of a few thousand call arguments and a time
  bound that the quadratic version cannot meet (count work, not wall time, if the sink
  exposes a counter).
- Re-emit long quoted lists with bounded operand depth and re-measure `wasmtime compile`
  at 5,000 / 20,000 symbols; `WideListStackTest`'s quoted-symbols leg is the size to beat.
