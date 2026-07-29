# The JVM `%ERROR-RUNTIME` dispatch chain overflows the 16-bit branch encoding past ~163 condition classes

Found 2026-07-29 while landing `.todo/206` (measuring the report renderer's size
against the registry). **Pre-existing, NOT a regression of that change** -- at the
threshold the method measures 32574 bytes before it and 32560 bytes after, i.e.
todo-206 made it 14 bytes *smaller*.

`todo-115` moved the computed-`error` condition-type dispatch out of the call site
(90 KB inline per site) into one shared injected defun plus one small construction
helper per class -- `LispMacroExpander.runtimeErrorDefuns`, described in
`.kb/error-handling.md` "Compiled runtime condition-type dispatch" and
`.kb/jvm-method-size-limits.md` "Registry-proportional expansions". The per-class
helpers (`%ERROR-RT-n`) are bounded. **The dispatch defun itself is not**: it is one
`cond` clause per registered CONDITION class, and on the JVM a `cond` lowers to
NESTED `if`s, so the OUTERMOST arm's else-branch spans every remaining arm. The
chain therefore hits the signed-16-bit branch limit long before the 64 KB code
limit the segmentation budgets were sized for.

## The measurements

`%ERROR-RUNTIME` grows ~195 bytes per registered condition class (the seeded 21
included):

| user condition classes | `%ERROR-RUNTIME` size |
| --- | --- |
| 60 | 16960 |
| 90 | 22810 |
| 120 | 28660 |
| 141 | 32755 |
| 142 | 32950 |
| 143 | `branch offset 32952 ... overflows the signed 16-bit branch encoding` |

Reproduction (JVM backend only -- the interpreter and both wasm-GC backends
compile and run the same program at 300 classes):

```lisp
;; N x (define-condition big-eK (simple-error) ())
(print (handler-case 1 (error (e) 2)))
```

```console
$ rontolisp b143.lisp -o B.class
Exception in thread "main" java.lang.IllegalStateException: while compiling defun
%ERROR-RUNTIME: branch offset 32952 at position 4482 overflows the signed 16-bit
branch encoding (method body too large)
```

Note the trigger: a bare `handler-case` is enough to put
`needsRuntimeErrorDispatch` on, so the failure does NOT need the program to
contain a computed `(error type ...)` at all -- it needs only a catching form plus
a large condition hierarchy.

## Why nothing hits it today

The `.kb` "measured at cl-postgres scale -- 165 registered classes" figure counts
ALL classes; only the CONDITION ones enter this chain, and cl-postgres stays under
the threshold. So this is a latent ceiling, not a broken build -- but it is the
next thing a bigger library tree (or two libraries' condition hierarchies in one
program) will reach, and it fails at COMPILE time with no workaround short of
`--dynamic`.

## Scope

- Bound `%ERROR-RUNTIME` by construction, the way its siblings already are. Two
  shapes are precedented:
  - **Segment the chain** like `JvmRuntimeBuilder.buildDispatchMethods`
    (`_invoke_9`, `_invoke_9$1`, ...) and `JvmEvalRuntimeBuilder
    .buildLookupSegments` (`_lookup`, `_lookup$1`, ...): each segment falls
    through to the next. Those are hand-emitted bytecode, though, while this
    dispatch is GENERATED LISP -- the segmentation would have to be expressed as
    `(defun %error-runtime (d a) (if <first 40 arms> ... (%error-runtime$1 d a)))`,
    which is straightforward and keeps one shared implementation for all backends.
  - **Turn it into a DATA table** like the `typep`/`subtypep` dispatches
    (`%SUBTYPEP-ANCESTOR-TABLE` &c): a name-to-helper-index lookup plus one
    indexed call. This needs an index-to-function call, i.e. the funcall table,
    which the current shape deliberately avoids -- weigh that against the size
    win before choosing.
- Whichever shape wins, apply it on ALL FOUR backends (the generated defun is
  shared), even though only the JVM has the hard limit: a per-backend split here
  would be a divergence with no reason behind it.
- Add the measurement to `.kb/jvm-method-size-limits.md`'s "What is kept bounded"
  list -- it is currently the one registry-proportional expansion that section
  claims is bounded and is not.

## Non-goals

- A general `GOTO_W` / branch-relaxation pass in `am.ik.jvm` (the `.kb` records
  that `GOTO_W` exists and no emitter uses it). It would fix this symptom and the
  next one, but it is a much bigger change than bounding one generated defun, and
  the 64 KB code limit would still be there.
- The `%ERROR-RT-n` helpers: already one small defun per class, nothing to do.

## Verification

- A `JvmLispCompilerTest` case that COMPILES (and runs) a program with enough
  condition classes to cross the old threshold -- ~200, to leave headroom -- and
  asserts a computed `(error ty :initarg v)` still dispatches to the right class.
  It must RUN the class, not just compile it: a segmentation bug shows up as a
  `VerifyError` at load time.
- `-Drontolisp.jvm.debug-method-sizes=true` on that program: every emitted body
  under the segmentation budget.
- The existing `runtime-type-dispatch-residue` /
  `runtime-type-dispatch-and-symbol-designators` ci-spec cases must keep their
  output byte-identical (they are far below the threshold, so the segmentation
  must not change what a small program emits -- or, if it does, the change has to
  be deliberate and the native E2E re-run).
