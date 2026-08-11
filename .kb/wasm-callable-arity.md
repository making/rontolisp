# WASM backend: the callable-type arity limit, and the two ways past it

Scope: the **GC WASM backend** (`codegen.wasm`), Preview 1 and `--component`.
The JVM backend has no such limit and the interpreter has none either, so
everything here is a wasm-only lowering that must leave the other backends'
answers unchanged.

## The invariant

**A wasm DEFUN or LAMBDA takes at most `WasmLispCompiler.MAX_CALLABLE_ARITY`
(10) wasm parameters, a CALL SITE at most `callArityCeiling()` (10 plus what the
program itself asked for, capped at `MAX_EXTRA_CALL_ARITY` = 4 more), and no
program may observe either.** The fixed per-arity dispatchers
(`FUNC_DISPATCH_BASE + 0..10`) take one wasm parameter per Lisp argument, so
they stop at the first number; a call site past it gets a dispatcher APPENDED
(below), and a call past the ceiling is rewritten at the AST level, in
`WasmArityBundler`, before the `usesEval` scan that decides which runtime
pieces are emitted.

Three shapes reach the limit, and they need different treatments:

- **A DEFUN with more than 10 parameters** (split-sequence's 10-parameter
  `split-list`) is bundled: the surplus parameters become one list parameter and
  the direct call sites pass `(list ...)`. Only DIRECT calls are rewritten -- in
  Lisp-2 a head-position symbol is unambiguous -- so taking a function VALUE of
  a bundled function is a clear compile error.
- **A CALL SITE with 11..14 arguments through a function value** gets its own
  per-arity dispatcher, appended after the fixed block.
- **A CALL SITE past that** is spread: `(funcall f a1 .. a15)` becomes
  `(apply f (list a1 .. a15))`, which `_apply` hands to the SPREAD dispatcher
  (`FUNC_DISPATCH_SPREAD`) -- one function over every callable, taking the whole
  argument list as a single cons. That dispatcher already existed for `apply`
  through a computed designator; `WasmArityBundler.spreadOverArityFuncalls` is
  what lets `funcall` reach it.

## Why the call site is a separate rule

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
`#'` guard never fires, so before the spread rule the site compiled to
`LispMacroExpander.overArityFuncallStub` -- a call-time "not supported" signal,
which in a non-EH module is a bare `unreachable`. That was acceptable while the
only known sighting was cl-postgres' 9-argument SSL funcall on a dead branch; on
chipz's inflate path it was a trap on the hot path, with no compile-time warning.

## Why the ceiling is DERIVED and not just larger

`MAX_CALLABLE_ARITY` is an index ORIGIN, not merely a limit:
`FUNC_DISPATCH_SPREAD = FUNC_DISPATCH_BASE + MAX_CALLABLE_ARITY + 1` and every
`FUNC_*` after it is defined off that, as is every type index after
`TYPE_CALLABLE_BASE + MAX_CALLABLE_ARITY`. **Raising the constant moves indices
in every module**, which is exactly what the "appended after the last fixed
helper so no index above shifts" comments all over `WasmLispCompiler` exist to
prevent. So the extra tier is appended instead, with the same conditional-index
discipline `--simd`, async and instances use:

| block | placed at | shifts |
| --- | --- | --- |
| extra dispatchers `_dispatch_11..` | `extraDispatchFuncBase()` = after the `--simd` and async function blocks | `userFuncBase()` only |
| extra callable signatures | `extraCallableTypeBase()` = after the `--simd`, async and instance type blocks | `fixedTypeCount()` only |

Both of those were already read dynamically by every consumer, so **a program
whose widest call fits the fixed block is byte-identical to a build that never
knew about the tier.** Verified by compiling every `examples/console`,
`examples/net`, `examples/wit`, `examples/count-vowels`, `examples/ml`,
`examples/asdf` and `size-report/programs` source under four flag sets (304
artifacts) before and after: only zlib's four rows moved.

`extraCallArity` is derived in `compile` from
`WasmArityBundler.widestDispatchArity`, a pre-scan for the widest `funcall`
argument count or `mapcar`/`mapc`/`mapcan` list count. **Past the cap the whole
program falls back to the fixed ceiling** rather than splitting the difference:
a module that needs the spread dispatcher anyway gets every wide call through it
for free, and mixing the two would pay for both.

### Why 4 more, and not more than that

An extra ladder is one `br_table` over the callables of that arity: 975 B on
zlib, 41 B in a two-callable program. The spread dispatcher is one function over
EVERY callable at every width: 12,156 B on zlib -- **7.3% of the whole
`--optimize=size` artifact for one eleven-argument funcall** -- and 2,405 B even
in that two-callable program. So a handful of ladders is far cheaper than the
one function, and enough ladders is not.

The evidence for how many: across the 122 systems (1,886 files) of a populated
Quicklisp cache, the widest `funcall` anywhere is uiop's 13-argument

```lisp
(funcall 'ensure-pathname p :namestring :lisp :ensure-physical t
         :ensure-absolute t :defaults 'get-pathname-defaults
         :want-non-wild t :on-error nil)
```

with chipz's eleven next; every program measured wants exactly ONE extra arity.
Four is headroom over the whole observed corpus, not a fit to it.

## The map family shares the ceiling

`mapcar`/`mapc`/`mapcan` funcall their function once per element of each list,
so their LIST COUNT picks a per-arity dispatcher exactly as a funcall's argument
count does. They compute it through `WasmLispCompiler.mapDispatchFuncIndex`,
which is also where their ceiling check lives: past it there is no per-site
fallback (unlike `funcall`, which has the call-time signal), and the
unchecked `FUNC_DISPATCH_BASE + nLists` they used to compute silently addressed
the NEXT runtime helper and emitted a module that does not validate. They are in
`widestDispatchArity` for that reason, so eleven lists now compiles and runs.

## Ordering the passes depend on

The spread pass runs BEFORE the apply-runtime scan
(`LispMacroExpander.needsApplyRuntime`, `.kb/eval-runtime.md`; it was the
`usesEval` scan until todo-315 split the apply tier out of the interpreter), and
that ordering is load-bearing: the scan looks for `apply`, and the spread
dispatcher's BODY is only built when it answers true. Moving the rewrite into
the codegen branch would inject an `apply` the gate has already run past, and
the dispatcher would be an `unreachable` stub again. The injected
`(apply fun ...)` designator is a VARIABLE, so the scan's computed-designator
arm catches it. Conversely, when nothing is left to spread the gate now answers
FALSE for a program like zlib, and the 12 KB dispatcher becomes the stub it
should always have been there.

The ceiling itself must be known before Pass 2, because Pass 2 writes
`userFuncBase()`-relative indices into every direct call -- hence the AST
pre-scan rather than reading `indirectCallArities` back afterwards. A `funcall`
a macro synthesizes DURING Pass 2 is therefore invisible to it and still
compiles to the call-time signal if it is past the ceiling, exactly as before
the tier existed. For the same reason an extra dispatcher's slot exists as soon
as the scan sizes the tier, and its body is the unused-arity stub when Pass 2
never emitted a call to it.

**A context that does not carry the ceiling is the failure mode to watch.**
`WasmAsyncEmit.freshCtx` rebuilds a `Ctx` field by field, and it builds the
SYNCHRONOUS top level too, so a wide funcall at top level compiled to a
call-time signal while the same form inside a defun reached the dispatcher --
the same trap `instanceTypeIndex`/`layoutAddresses` carry their own "NOT
optional" comment about.

Pinned by `WasmLispCompilerIntegrationTest.compileFuncallWiderThanTheCallableLimitGoesThroughApply`
(the keyword shape and twelve positional arguments),
`compileFuncallEitherSideOfTheDerivedArityCeilingAnswersTheSame` (10/11/14/15 at
top level, which is the `freshCtx` pin),
`compileMapcarOverMoreListsThanTheFixedDispatcherBlockWorks`,
`WasmLispCompilerTest.aFuncallPastTheFixedDispatcherBlockCostsALadderAndNotTheSpreadDispatcher`
(the byte budget above) and the `fill-and-over-arity-funcall` ci-spec case;
`ChipzE2eTest` is the real-library consumer.
