# 452. `--component --optimize` narrows away the uncaught-condition report

Difficulty: Medium

Found 2026-08-19 by the `.todo/448` spike, which ran the whole `ci-spec.yaml`
corpus with `--optimize` injected into every compile. Two of 1,572 cases fail,
both on `WASM_COMPONENT`: `uncaught-condition-report` and
`uncaught-simple-error-report`.

**This is a pre-existing bug in `--optimize --component`, not something the flip
introduces** -- but the flip makes it the default, so it blocks `.todo/448`.

## The defect

```lisp
(print (handler-case (error "caught: ~a" 2) (error (e) (princ-to-string e))))
(error "boom: ~a" 42)
```

```
$ rontolisp uncaught.lisp -o u.component.wasm --component
$ wasmtime run -W gc=y -W exceptions=y u.component.wasm
"caught: 2"
Unhandled condition: boom: 42          <-- the report
Error: failed to run main module ...

$ rontolisp uncaught.lisp -o u.component.wasm --component --optimize
$ wasmtime run -W gc=y -W exceptions=y u.component.wasm
"caught: 2"
Error: failed to run main module ...   <-- the report is GONE
```

Preview 1 (`-o u.wasm --optimize`) and the JVM keep the line at every level; the
loss is `--component` only, at `DEFAULT` and at `SIZE` alike. The program still
exits as a trap, so the exit class is unchanged -- what is lost is the whole
diagnosis, which is the one thing `UncaughtReport` exists to guarantee "once, on
every backend".

## Root cause

`WasmLispCompiler` (~L6212) answers "can this program present fd 2" for
`WasmComponentBuilder.Narrowing` with a scan of the SOURCE:

```java
this.dynamic || programUsesSymbol(program, LispNames.ERROR_OUTPUT_VAR)
        || programUsesSymbol(program, LispNames.WARN)
        || programUsesSymbol(program, LispNames.WARN_INTERNAL)
```

and when it answers no, the wrapper retains the adapter's stdout-only
`fd_write`, which `unreachable`s on fd 2 by design.

The EH landing pad is a producer that scan cannot see.
`WasmUncaughtReportCompiler.reportForm` builds

```java
list(new LispSymbol(LispNames.WARN_INTERNAL),
     list(new LispSymbol(LispNames.STRING_CONCAT), new LispString(UncaughtReport.PREFIX), text));
```

-- it emits `%warn`, which IS one of the three names the scan looks for, but it
SYNTHESIZES it during Pass 2, long after the scan read the source. The program's
own text never spells `%warn`, so the scan says "no fd 2" and the report's write
becomes the trap the narrow half promises.

Confirmed by construction: adding `(let ((s *error-output*)) (declare (ignorable s)))`
to the program above brings the line back under `--component --optimize`.

`.kb/standard-output-redirect.md` predicted exactly this failure --

> Anything new that materializes `StreamDesignators.STANDARD_ERROR_HANDLE` must
> join that scan (`WasmComponentBuilder.Narrowing`, ...); miss it and
> `--optimize` turns that write into a trap.

-- and the landing pad (a later change) never joined it. The list was documented
as needing to stay complete, and it did not.

## The fix

The narrowing must be decided from the same fact that decides whether the
landing pad is emitted, not from a scan of the user's text. `ehMode` is already
computed at ~L2522 (`programUsesEhForm(program) || asyncMode || blockExitTag`),
i.e. well before the `Narrowing` is built, so OR-ing the landing pad's own
emission condition into the predicate is available without a new pass.

Prefer that over adding `%warn` to a post-expansion re-scan: the fourth
materializer (todo-283's `GLOBAL_ENV` seed) was made safe **by construction**
rather than by remembering, and the same reasoning applies here -- a producer
that the compiler INJECTS should contribute its own answer, not hope a text scan
finds it. Whatever shape the fix takes, make the two decisions read one
predicate so they cannot drift again.

## Watch

- `--component --no-wasi` returns before the narrowing (a reactor imports
  nothing), so the Cloudflare Workers and the browser reactor examples are not
  affected. The affected shape is the WASI 0.3 command component.
- The narrow/wide rule is "reject fd 2 rather than approximate it" -- keep it.
  Widening the answer is the safe direction (bytes, not a trap), and the ~185 B
  `wasi:cli/stderr` costs is the correct price for a program that can report.
- A serve component's handler failure is `.todo/191`, a different path; this
  item is the entry function's landing pad only.

## Acceptance

The two `ci-spec.yaml` standalone cases pass on `WASM_COMPONENT` with
`--optimize` and with `--optimize=size`, and a program with no reporting path
still drops `wasi:cli/stderr` (size-check a print-only component before and
after -- an unconditional widen would be a regression this item must not ship).
Add the `--optimize` leg to whatever pins the narrowing today, so this cannot
regress silently again.
