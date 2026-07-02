# `do`/`return` and the `%block` non-local exit boundary

`do` is a macro (`LispMacroExpander.expandDo`) expanding to a `let`/`while` loop with parallel-stepped vars (assigned through temporaries). `do`/`dolist`/`dotimes` wrap their expansion in the internal `%block` special form (`LispNames.BLOCK_INTERNAL`, a `CL_INTERNALS` symbol); `return` (`LispNames.RETURN`) is a non-local exit to the **nearest** enclosing `%block`.

Per backend:
- Interpreter throws `LispReturnSignal` (stack-trace-free) caught by `evalBlock`.
- JVM stores the value into a local then `goto`s the block exit (`JvmBlockCompiler`/`JvmReturnCompiler`, `Ctx.blockTargets`) — store-then-jump keeps the operand stack empty at the merge point (version-50 verifier).
- WASM emits `block (result (ref null eq))` and `return` is a `br` at depth `Ctx.wasmCtrlDepth - marker` (`WasmBlockCompiler`/`WasmReturnCompiler`; `wasmCtrlDepth` is bumped only by `if` (+1) and `while` (+2)).

Consequence: `return` works only where the surrounding operand stack is empty (an `if`/`when` branch or a loop-body statement), not mid-expression. `member`/`assoc` are themselves expanded through `do`/`return` with an `(atom cursor)` end-test. The runtime `_eval` interpreters do not know `do`/`return`/`%block` (README).
