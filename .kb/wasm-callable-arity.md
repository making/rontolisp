# WASM backend: the callable-type arity limit, and the two ways past it

Scope: the **GC WASM backend** (`codegen.wasm`), Preview 1 and `--component`.
The JVM backend has no such limit and the interpreter has none either, so
everything here is a wasm-only lowering that must leave the other backends'
answers unchanged.

## The invariant

**A wasm callable takes at most `WasmLispCompiler.MAX_CALLABLE_ARITY` (10) wasm
parameters, and no program may observe that.** The per-arity dispatchers
(`FUNC_DISPATCH_BASE + 0..10`) take one wasm parameter per Lisp argument, so
they stop there; the limit itself is not raised because the type and function
indices past it are what the pinned `--component` adapter blobs depend on.
Everything wider is rewritten at the AST level, in `WasmArityBundler`, before
the `usesEval` scan that decides which runtime pieces are emitted.

Two shapes reach the limit, and they need opposite treatments:

- **A DEFUN with more than 10 parameters** (split-sequence's 10-parameter
  `split-list`) is bundled: the surplus parameters become one list parameter and
  the direct call sites pass `(list ...)`. Only DIRECT calls are rewritten -- in
  Lisp-2 a head-position symbol is unambiguous -- so taking a function VALUE of
  a bundled function is a clear compile error.
- **A CALL SITE with more than 10 arguments through a function value** is
  spread: `(funcall f a1 .. a11)` becomes `(apply f (list a1 .. a11))`, which
  `_apply` hands to the SPREAD dispatcher (`FUNC_DISPATCH_SPREAD`) -- one
  function over every callable, taking the whole argument list as a single cons.
  That dispatcher already existed for `apply` through a computed designator;
  `WasmArityBundler.spreadOverArityFuncalls` is what lets `funcall` reach it.

## Why the second one is a separate rule

A KEYWORD lambda list is how a program reaches the limit in practice, and the
argument count at the call site is not the callee's parameter count: the
keywords go through VERBATIM for the callee's own dispatcher to parse, so three
required parameters and four keywords is **eleven** arguments. chipz's

```lisp
(funcall fun state input output :input-start s :input-end e
                                :output-start s :output-end e)
```

is exactly that, for `%inflate`, whose lambda list has seven parameters. The
defun-side bundler never sees it (nothing about `%inflate` is too wide) and its
`#'` guard never fires, so before this rule the site compiled to
`LispMacroExpander.overArityFuncallStub` -- a call-time "not supported" signal,
which in a non-EH module is a bare `unreachable`. That was acceptable while the
only known sighting was cl-postgres' 9-argument SSL funcall on a dead branch; on
chipz's inflate path it was a trap on the hot path, with no compile-time warning.

The pass runs BEFORE the apply-runtime scan
(`LispMacroExpander.needsApplyRuntime`, `.kb/eval-runtime.md`; it was the
`usesEval` scan until todo-315 split the apply tier out of the interpreter), and
that ordering is load-bearing: the scan looks for `apply`, and the spread
dispatcher's BODY is only built when it answers true. Moving the rewrite into
the codegen branch would inject an `apply` the gate has already run past, and
the dispatcher would be an `unreachable` stub again. The injected
`(apply fun ...)` designator is a VARIABLE, so the scan's computed-designator
arm catches it.

Pinned by `WasmLispCompilerIntegrationTest.compileFuncallWiderThanTheCallableLimitGoesThroughApply`
(the keyword shape and twelve positional arguments) and the
`fill-and-over-arity-funcall` ci-spec case; `ChipzE2eTest` is the real-library
consumer.
