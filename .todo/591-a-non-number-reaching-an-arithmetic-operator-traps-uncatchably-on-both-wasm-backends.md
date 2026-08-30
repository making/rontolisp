# A non-number reaching an arithmetic operator traps uncatchably on both WASM backends

Difficulty: High

Found 2026-08-31 while closing `.todo/589` (`file-length` answering nil on WASM).
589 was the wrong answer; THIS is why the wrong answer killed the program instead
of being handled, and it is a much wider gap than the one operator that surfaced
it.

## The measurement

Compiled and run on all four backends, 2026-08-31, from
`target/rontolisp-0.1.0-SNAPSHOT-exec.jar` at the 589 branch tip:

```lisp
(defun try (thunk) (handler-case (funcall thunk) (error (e) :caught)))
(defun a (x) (+ 1 x))
(defun b (x) (< 1 x))
(defun c (x) (* 2 x))
(defun d (x) (max 1 x))
(print (try (lambda () (a nil))))     ; nil into +
(print (try (lambda () (b nil))))     ; nil into <
(print (try (lambda () (c "x"))))     ; a string into *
(print (try (lambda () (d 'sym))))    ; a symbol into max
```

| backend | result |
| --- | --- |
| interpreter | `:CAUGHT` x4 (a `type-error`) |
| JVM `-o Prog.class` | `:CAUGHT` x4 (a `simple-error`) |
| WASM preview 1 | `wasm trap: cast failure` on the FIRST one; nothing printed |
| WASM `--component` | `wasm trap: cast failure` on the FIRST one; nothing printed |

The program is in EH mode (it has a `handler-case`), so the machinery that
would catch a real condition is present and compiled in -- the trap goes
straight past it, because a `ref.cast` failure is a wasm trap and not a wasm
exception. `handler-case`, `ignore-errors` and `unwind-protect` are all equally
powerless.

Two facts that bound the shape of a fix:

- `(min 4096 (file-length in))` was the ORIGINAL reproduction (589) -- an
  ordinary portable caller taking a documented nil answer, ending in a trap.
- An uncaught `(error "boom ~a" 1)` in a NON-EH module is already a bare
  `unreachable` with no message on both WASM backends (measured the same day:
  the interpreter and the JVM print `Unhandled condition: boom 1`). So the
  no-message ending is not unique to this bug -- but the UNCATCHABILITY is, and
  it is the half that matters.

## Where it comes from

Two emit sites, both in `codegen/wasm`:

- **the float/general path**: `WasmEmitHelper.emitAsF64FromLocal` (the body of
  the shared `_as_f64`, `FUNC_AS_F64`) is a ladder -- i31, `TYPE_BIGNUM`,
  `TYPE_BIGINT`, ratio -- whose final `else` is a bare
  `ref.cast TYPE_FLOAT`. Anything that is not a number reaches that cast and
  traps. `.kb/wasm-shared-coercion.md` is the file that owns this function.
- **the integer fast paths**: `WasmEmitHelper.castI31GetS`, inlined at the
  arithmetic / comparison / fusion sites (`WasmArithCompiler`,
  `WasmComparisonCompiler`, `WasmIntFusionCompiler`, and `NoGcWasmCompiler`'s
  own arms), is `ref.cast i31` + `i31.get_s` with no test in front of it.

Both are on the HOT path of every numeric program, which is the whole difficulty
of the item: the test must not cost anything a well-typed program can feel.

## What the fix probably looks like

Not decided -- this is the design work the item is for. What is known:

- The answer has to be a **Lisp condition**, not a trap: a `type-error` the way
  the interpreter raises one, so `handler-case` catches it in EH mode. There is
  already `WasmErrorCompiler` + the EH-mode landing pad
  (`WasmUncaughtReportCompiler`) to reach.
- A NON-EH module cannot catch anything by construction, so the honest target
  there is the same "print the report on fd 2, then end" that an uncaught
  condition should give -- which is ALSO missing today (see the second measured
  fact above), so the two are one piece of work.
- The natural single seam is `_as_f64`: it is already ONE shared function
  (43% of a float module's code section before it was hoisted --
  `.kb/wasm-shared-coercion.md`), so its final `else` can become a call to a
  shared `_type_error` runtime for the cost of nothing on the paths that hit an
  earlier rung. The i31 fast paths are the harder half: they are inlined by
  design and a `ref.test` + branch at every one of them is exactly the code the
  hoisting of `_as_f64` existed to remove. Measure before choosing --
  `.kb/wasm-shared-coercion.md` and `size-report/` have the method and the
  baseline.
- `--no-gc` has no conditions at all and should keep trapping; say so rather
  than half-wiring it.

## What "done" requires

- The four cases above answer `:CAUGHT` on all four backends, and an uncaught
  one prints the same `Unhandled condition:` report the interpreter and the JVM
  print.
- Unit coverage in `WasmLispCompilerIntegrationTest` (both the P1 and the
  `component` twin) plus a `ci-spec.yaml` case, so the four are pinned
  byte-identical.
- A size/speed measurement of the added tests on the float and integer hot
  paths, written into `.kb/wasm-shared-coercion.md` with its date -- if the
  integer half costs more than it is worth, landing the `_as_f64` half alone
  and RECORDING the measurement is a complete result.
- `.kb/error-handling.md` and `.kb/wasm-shared-coercion.md` updated together;
  `doc/{en,ja}` wherever the WASM backends' error behaviour is described.
