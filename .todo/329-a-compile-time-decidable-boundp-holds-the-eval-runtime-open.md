# A compile-time-decidable `boundp` holds the eval runtime open

Difficulty: Medium

`(unless (boundp '+K+) (defconstant +K+ v))` is the standard redefinition-safe
`defconstant`, and chipz writes it through its own `define-constant` macro -- 12 of them
in the zlib row. Each one is a TOP-LEVEL test of a constant the same program defines two
tokens later, and `boundp` sits in the `usesEval` OR-chain on BOTH compile paths
(`JvmLispCompiler` ~L980, `WasmLispCompiler` ~L1915), which emits the interpreter's
`_eval` / `_lookup` / global mirror and forces EVERY arity dispatcher.

Measured on an isolated quicklisp cache with chipz's `define-constant` rewritten to expand
to a bare `defconstant`, both modules still gunzipping the fixture byte for byte:

| zlib `--optimize=size` | bytes |
| --- | ---: |
| as it is | 107,628 |
| with the `boundp` guard gone | 105,393 (**-2.1%**) |

Repro: copy `systems.txt`, `releases.txt` and `software/chipz-*` out of
`~/.rontolisp/quicklisp` into a scratch dir, point `RONTOLISP_QUICKLISP_HOME` at it, patch
the macro, compile `size-report/programs/zlib/zlib.lisp` both ways.

The tier costs against a `(print (+ 1 2))` = 423 B baseline, same flag: `apply` +10.8 KB,
`boundp` +20.2 KB, `eval` +141 KB. On zlib the two overlap -- chipz's own `apply` (11
sites, all in `CHIPZ:DECOMPRESS`) keeps most of that machinery whatever happens to the
`boundp` -- which is why the delta is 2,235 B and not the 9.5 KB the probes suggest.
Measure the row, do not extrapolate the probe.

Worth doing because the idiom is not chipz's: `unless (boundp` appears in chipz, yason,
iterate, mgl-pax, global-vars and slime in the local dist alone.

## The shape to build

A top-level `(boundp 'literal-symbol)` is a compile-time fact. The program is closed on
the compile paths, so the answer is "did an EARLIER top-level form make this name a
global", and `compiler/GlobalVarCollector` already answers exactly that question in
declaration order (`defvar`/`defparameter`/`defconstant` names plus bare-symbol top-level
`setq`/`setf` places).

Fold it in the shared front end (`compiler/`, beside `ToplevelStatements` and the
pure-builtin fold), always on rather than behind `--optimize` -- neither direction is a
trade -- so the gate scan that runs later simply does not find a `boundp` to trip over.

## Watch

- **Position is the whole subtlety.** `(boundp 'x)` before x's `defvar` is nil and after it
  is t; the fold needs the prefix of the top-level list, not the whole-program set that
  `GlobalVarCollector.collect` hands back today.
- **The soundness gate is free.** The fold is only unsound when a global can spring into
  existence at run time -- `set` / `(setf (symbol-value ...))` of a computed name, `eval`,
  `load`, `--dynamic`. Every one of those already forces `usesEval` on its own, so the
  same condition that makes the fold unsound makes it pointless: gate on it and lose
  nothing.
- **The interpreter keeps evaluating `boundp`** -- there the question is live, and its
  error text is pinned.
- **What the fold leaves behind.** `(unless (boundp '+K+) (defconstant +K+ v))` collapses
  to the `defconstant`, which is what the file means; check `ClRedefinitionWarnings` does
  not start warning about a constant that now looks unconditionally defined.
- `fboundp` is the same OR-chain arm and the same idiom shape, but its answer is the
  FUNCTION registry rather than the globals table. In scope only if it falls out for free.

## Adjacent, measured, NOT in this item

Both are one question -- what the gate FORCES, rather than what opens it -- and want their
own audit. Numbers from the shaken zlib JVM class (84,476 B of bytecode, 355 methods;
71% of it is chipz's own defuns/lambdas/top-level and 29% is machinery):

- **`apply` pulls the whole interpreter in.** `_eval` is 4,031 B of that class and the
  program never evals. The JVM chain lists `apply` directly, while the WASM side has the
  todo-315 apply tier that resolves symbol designators WITHOUT the interpreter
  (`.kb/eval-runtime.md`). Whether the JVM can take the same tier is unaudited.
- **`usesEval` forces EVERY arity dispatcher** (`for (arity = 0..MAX)
  indirectCallArities.add(arity)`), so zlib carries `_invoke_5/7/9/11` for arities nothing
  funcalls. The ladder family is 12,432 B, 14.7% of the class -- the single biggest
  machinery item left, and todo-323/328 only narrowed WHO enters it, never what it costs
  to emit.

## Deliverable

The fold on both compile paths with the interpreter unchanged, a pin that a program whose
only `boundp` is this idiom emits no eval runtime (and that one whose `boundp` is genuinely
computed still does), the four-backend ci-spec answer unchanged, the zlib row re-measured,
`./mvnw test` and the native `CiSpecE2eTest` green.
