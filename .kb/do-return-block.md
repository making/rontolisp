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
`(loop named foo ...)` wraps the loop expansion in `(block foo ...)`
(`LispMacroExpander.LoopExpander`, all backends), so `(return-from foo v)` exits it
-- lite: the implicit `%block` stays inside, so plain `return` still exits the loop
(CL says a named loop has no nil block).

**Named `block`/`return-from` on the COMPILE PATH (JVM + wasm-GC, LEXICAL)**: the
compilers implement named blocks as real goto/br targets keyed by name, within one
compiled function. Machinery:

- **`%fn-block` function boundary** (`LispNames.FN_BLOCK_INTERNAL`, a `CL_INTERNALS`
  symbol): `LambdaLists.wrapReturnFrom` — run from `expand()` (the lambda compilers'
  `toNative` path, block name nil, idempotent re-entry check) and from
  `desugarProgram`'s defun rebuild (block name = the defun's name; setf-functions use
  the PLACE name via `setfFunctionPlaceName`, mirroring `evalDefun`) — wraps a
  `return-from`-containing body in `(%fn-block name body...)`. The scan still stops at
  nested `lambda`/`defun` boundaries (`containsReturnFrom`). The interpreter passes
  `expand(..., false)` and keeps its native dynamic blocks.
- **Target resolution**: `JvmLispCompiler.BlockTarget` / `WasmLispCompiler.BlockMarker`
  carry `(name, catchesPlain, functionBoundary)`. Plain `return` targets the nearest
  `catchesPlain` block (`%block` or `(block nil ...)`), SKIPPING named blocks — the
  goto/br analog of the interpreter's signal transparency. `(return-from name v)`
  (`JvmReturnFromCompiler`/`WasmReturnFromCompiler`) targets the nearest block whose
  name matches — a user `(block name ...)` (`compileNamed`) or the `%fn-block`
  (`compileFnBlock`) — falling back to the nearest `functionBoundary` when no name
  matches, so an unmatched `return-from` exits the current function. `(return-from nil
  v)` compiles as plain `return`. `(block nil ...)` compiles exactly like `%block`.
  `LispMacroExpander.blockName` mirrors the interpreter's designator handling
  (nil/`"nil"` → the nil block).
- **Unwind interplay**: on the JVM, `JvmReturnCompiler.emitExit` is the shared exit
  sequence generalized to any target depth (escaped `unwind-protect` cleanups inline
  with hole recording, `handler-case` spill restore, operand-stack unwind — the
  comparisons use the target's 1-based block-stack depth instead of the stack size).
  On WASM, plain `return` keeps the pre-built trampoline cascade (its continuation now
  computed against the nearest `catchesPlain` marker), while `return-from` INLINES the
  escaped scopes' cleanups at the exit site and brs straight to the target — the same
  strategy and lite limit as `go` (a throw from an inlined cleanup can re-enter its own
  handler).
- **The remaining deviation from CL** (pinned by
  `compileAndRunReturnFromInsideLambdaStaysLambdaLocal` and assoc-utils todo-086's
  `alistp`): a `return-from` inside a lambda whose name only matches a block in the
  lexically enclosing function exits *that lambda* (the `%fn-block` fallback), NOT the
  outer defun — a goto cannot cross into a separately compiled method. The interpreter's
  dynamic-extent crossing still differs there. `--no-gc` keeps the old name-dropping
  `expandBlock` lowering and has no `return-from` at all (it never ran
  `desugarProgram`).

Needed by cl-utilities' `rotate-byte`/`read-delimited` (function-scoped early returns,
now exact) and cl-ppcre (`ClPpcreE2eTest`, all four backends: `(block scan ...)` in the
generated scanner closures, `collect-char-class` returning across a `loop`).

## `tagbody`/`go` + `prog`/`prog*`

`tagbody`/`go` and `prog`/`prog*` work on all three backends (interpreter, JVM,
wasm-GC).

- **Interpreter = dynamic `go`**: `go` throws a `GoSignal` exception; `evalTagbody`
  catches it and re-enters at the target label via label-indexed re-entry. Because it
  is a thrown signal, a dynamic `go` **crosses function boundaries** (the target need
  not be lexically enclosing).
- **Compilers = LEXICAL subset only**: a `go` must target a lexically enclosing
  `tagbody` in the SAME compiled function; a non-lexical `go` is unsupported on the
  compile path.
  - **JVM**: `JvmTagbodyCompiler` lowers to goto/patch, with every label emitted as a
    `joinShape` join point at the tagbody's entry stack shape. `JvmGoCompiler` performs
    the escaped-cleanup/spill unwind (inlining escaped `unwind-protect` cleanups,
    restoring `handler-case` spills, operand-stack unwind) mirroring `return`
    (`JvmReturnCompiler`).
  - **WASM**: `WasmTagbodyCompiler` emits a dispatch loop plus a `br_table` over the
    segment blocks, using an `i31`-boxed pc. `go` inlines escaped `unwind-protect`
    cleanups at the branch site (same strategy/limit as `return-from` above). It
    **rejects `await` inside** a tagbody.
- **`prog`/`prog*`** = `%block` + `let`/`let*` + `tagbody`, so a user `(return x)`
  inside a `prog` exits the prog (via the `%block` boundary).
