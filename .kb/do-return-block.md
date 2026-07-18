# `do`/`return` and the `%block` non-local exit boundary

`do` is a macro (`LispMacroExpander.expandDo`) expanding to a `let`/`while` loop with parallel-stepped vars (assigned through temporaries). `do`/`dolist`/`dotimes` wrap their expansion in the internal `%block` special form (`LispNames.BLOCK_INTERNAL`, a `CL_INTERNALS` symbol); `return` (`LispNames.RETURN`) is a non-local exit to the **nearest** enclosing `%block`.

Per backend:
- Interpreter throws `LispReturnSignal` (stack-trace-free) caught by `evalBlock`.
- JVM stores the value into a local then `goto`s the block exit (`JvmBlockCompiler`/`JvmReturnCompiler`, `Ctx.blockTargets`) — store-then-jump means the exit is reached with the operand stack the block was *entered* with (`BlockTarget.entryStack`) on every path, which the verifier requires (and which lets the `StackMapAugmenter` post-pass compute one consistent frame per merge point — [[stackmap-augmenter]]). A `return` mid-expression therefore **discards** the operands the abandoned expression had already evaluated (`JvmReturnCompiler.emitStackUnwind`, over `Ctx.stack` — see `.kb/error-handling.md`); before that it silently emitted a class the verifier rejects.
- WASM emits `block (result (ref null eq))` and `return` is a `br` at depth `Ctx.wasmCtrlDepth - marker` (`WasmBlockCompiler`/`WasmReturnCompiler`; `wasmCtrlDepth` is bumped only by `if` (+1) and `while` (+2)). A `br` discards the operands above the label's arity for free, which is why wasm never had the JVM's problem.

`return` works mid-expression on all four backends (`(list "seen" (if (= x 2) (return :done) x))`), pinned by `JvmLispCompilerTest.compileAndRunReturnInArgumentPosition` and the ci-spec `handler-case-in-argument-position` case. `member`/`assoc` are themselves expanded through `do`/`return` with an `(atom cursor)` end-test. The runtime `_eval` interpreters do not know `do`/`return`/`%block` (README).

**Named `block`/`return-from` (INTERPRETER)**: `evalNamedBlock`/`evalReturnFrom` in
`LispEvaluator` implement REAL named exits: `(return-from name v)` throws
`BlockReturnSignal(name, v)`, caught by the nearest enclosing `(block name ...)`
whose name string-matches; `(block nil ...)` also catches the plain
`LispReturnSignal` (the loop macros' implicit nil block) and `(return-from nil v)`
throws that plain signal. `%block` catches ONLY `LispReturnSignal`, so the named
signal passes through loops -- that transparency is what lets cl-ppcre's
`collect-char-class` exit the function from inside a `loop` whose after-loop code
must NOT run. `evalDefun` wraps the (LambdaLists-expanded, rewrite SKIPPED via the
3-arg `LambdaLists.expand(…, false)`) body in `(block <function-name> ...)`
(setf-functions use the place name); `expandDefmethod` (shared) wraps method bodies
in `(block <generic-name> ...)`; lambdas get NO block, so a `return-from` inside a
lambda called within the extent exits the named function, as in CL. An unmatched
named signal surfaces as "no enclosing block named X" at the top-level eval entry.
`loop named` is still unsupported (cl-ppcre's bmh path avoids it because
`*use-bmh-matchers*` defaults nil).

**`return-from` on the COMPILE PATH (lite, name dropped)**: `LambdaLists.rewriteReturnFrom` — shared by the interpreter's lazy `expand()` and the compilers' `desugarProgram` (which now also rebuilds a plain-lambda-list defun/lambda whose body contains `return-from`) — rewrites every `(return-from name value)` in a defun/lambda body to `(return value)` and wraps the whole body in `(%block (progn ...))`, so it returns from the function. The block NAME is ignored; a `return-from` nested inside a `do`/`loop` therefore exits that loop's (nearer) block instead, which is only equivalent when the loop is the function's final form (the read-delimited/loop-`finally` idiom). Quoted data is exempt. `block` itself is not supported. Classified as a `CL_MACROS` entry; needed by cl-utilities' `rotate-byte` (early return) and `read-delimited` (`finally (return-from ...)`). **The rewrite stops at a nested `lambda`/`defun` boundary** (`containsReturnFrom`/`stripReturnFrom` do not descend into them): a `return-from` is scoped to its **nearest enclosing function**, so a `return-from` inside a lambda passed to `mapl`/`reduce`/etc. exits *that lambda* (the lambda's own `expand()` wraps it in its own `%block`), NOT the outer defun. This is a deviation from CL's true non-local exit (which the compilers cannot do across the lambda's separately compiled method — the stripped `return` would land in the lambda with no enclosing block) but keeps all four backends consistent (assoc-utils todo-086's `alistp` does `(return-from alistp nil)` inside a `mapl` lambda; as a lite lambda-local exit `alistp` returns t for any cons).
