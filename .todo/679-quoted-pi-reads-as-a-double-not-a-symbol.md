# `'pi` reads as a double, not a symbol -- the read-time constant class is not quotable

Difficulty: High

`reader/LispReader.readSymbol` substitutes a value for a fixed set of standard
constant names BEFORE package resolution runs (`CL_READ_TIME_CONSTANTS`, built by
`clReadTimeConstants()`), so the substitution happens wherever the spelling
appears -- including under `quote`, inside quoted list data, and as a `let`
variable name:

```lisp
(symbolp (car '(pi)))              ; => NIL      (SBCL: T)
'pi                                ; => 3.141592653589793
(car '(most-positive-fixnum))      ; => 9223372036854775807
(car '(single-float-epsilon))      ; => 5.960465188081798e-8
(car '(lambda-list-keywords))      ; => '(&ALLOW-OTHER-KEYS ...)   -- a nested QUOTE
(let ((x 'double-float-epsilon)) x); => 1.1102230246251568e-16
```

The affected names are the whole `CL_READ_TIME_CONSTANTS` set: `PI`, the 28
float constants from `CL_FLOAT_CONSTANTS` (`most-positive-*-float`,
`least-*-normalized-*-float`, `*-float-epsilon`, `*-float-negative-epsilon` for
SHORT/SINGLE/DOUBLE/LONG), `most-positive-fixnum`, `most-negative-fixnum`,
`array-dimension-limit`, `array-total-size-limit`, `char-code-limit`,
`internal-time-units-per-second`, `lambda-list-keywords`. (`NIL` and `T` go
through the same path and are correct there -- they ARE self-evaluating.)

## Why it is worth doing

The ANSI suite's `universe.lsp` -- loaded before EVERY chapter -- builds
`*floats*` with

```lisp
(loop for sym in '(pi most-positive-short-float ... long-float-negative-epsilon)
      when (boundp sym) collect (symbol-value sym))
```

so the quoted list arrives as 34 floats and the run dies on
`BOUNDP expects a symbol, got 3.141592653589793`. `*floats*` never gets defined,
and `*numbers*`, `*reals*`, `*rationals*`, `*universe*`, `*mini-universe*`,
`*classes*`, `*built-in-classes*` and `*array-dimensions*` all fall over behind
it. Measured on the checked-in `ansi-test/results/logs/`: **618 tests lost to
that one aux form**, spread over every chapter; the committed
`results/interpreter.md` shows the same cascade at ~700
(`*MINI-UNIVERSE*` 233, `*UNIVERSE*` 200, `*NUMBERS*` 80, `*FLOATS*` 65,
`*REALS*` 50, ...). It is the single largest lever on that report -- roughly 4%
of the corpus behind one reader decision.

It is also the reason the report's "Most frequent failure reasons" table cannot
be read as a ranked gap list today: five of its top eight rows are this one
cascade, not five missing operators.

## Why it is High, not a one-line delete

The substitution exists for a stated reason, recorded in the comments at the
site: the value of `most-positive-fixnum` / `array-dimension-limit` is
BACKEND-DEPENDENT (WASM fixnums are unboxed i31, so the limit differs), and
fixing them at read time is what gives all four backends parity for free without
a per-backend global. Deleting the substitution moves that problem, it does not
solve it.

The essential shape is a real CONSTANT BINDING rather than a reader rewrite:
the name stays a symbol through read, and each backend defines the global with
its own value (`Environment.createGlobal` for the interpreter, the equivalent
seed on the JVM and both WASM paths, `PackageRegistry.CL_SYMBOLS` so it is not
misclassified). A constant-folding pass may then substitute the value at a
CODE-position reference -- which is where the parity argument actually applies
-- while a reference under `quote` stays the symbol. `compiler/CompileTimeBoundp`
(`.kb/compile-time-boundp.md`) is the precedent for a fold that answers a
whole-program question at compile time.

## Pinning

Cross-backend behavior, so it needs the pinning `.kb/` file this class does not
have yet -- there is no `.kb` topic for `CL_READ_TIME_CONSTANTS` today; write
one (`read-time-constants.md`) and name the test in it. At minimum:

- `LispEvaluatorTest` / `JvmLispCompilerTest` /
  `WasmLispCompilerIntegrationTest`: `(symbolp (car '(pi)))` is `T`,
  `(boundp 'pi)` is `T`, `(symbol-value 'pi)` is the constant, `pi` in code
  position still folds.
- a `ci-spec.yaml` case for the four-backend agreement, including
  `most-positive-fixnum` where the VALUE legitimately differs per backend.
- Re-run `ansi-test/measure.sh` afterwards: the win is a chapter-wide drop in
  `*UNIVERSE*`/`*MINI-UNIVERSE*` unbound rows, and it is what makes the
  report's reason table mean something.

Found while re-evaluating the ANSI measurement method (2026-09-03). Siblings:
`.todo/680` (raw Java exceptions escape `handler-case`) and `.todo/681` (the driver
books those escapes as lost forms instead of failing tests).
