# The JVM carries the interpreter because a program `apply`s

Difficulty: Medium

Carried out of todo 329, which closed the `boundp` arm of the `usesEval` OR-chain and
found that arm was never what held the zlib JVM class open. `apply` is. The two levers
below are one question -- what the gate FORCES, rather than what opens it -- and neither
was audited.

Baseline after todo 329 (`size-report/programs/zlib/zlib.lisp`, `--optimize=size`,
class named `Zlib`): **181,768 B, 354 methods, 78,056 B of bytecode**. Re-measure against
this; the numbers todo 329 inherited were taken before it.

## 1. `apply` pulls the whole interpreter in (JVM)

`_eval` is 4,031 B of that class and the program never evals. The JVM chain lists
`apply` (and `multiple-value-call`, and `#'funcall`) directly, while the WASM side has
todo 315's apply tier that resolves literal-target designators WITHOUT the interpreter
(`.kb/eval-runtime.md`). chipz has 11 `apply` sites, all in `CHIPZ:DECOMPRESS`.

`.kb/eval-runtime.md` states the reason the JVM keeps the wider gate -- the
always-injected wrapper bodies reference `_apply` and the post-compile self-check
(`gateGroupFor`/`GateUnderpredicted`) forces `GROUP_EVAL` back on, so narrowing the gate
would only buy a guaranteed re-compile pass. **That is the claim to test first**: it was
written as a prediction, not a measurement. If it holds, the item is only worth the note;
if the wrapper set can be reference-gated the way the WASM spread tier is, the tier
follows.

## 2. `usesEval` forces EVERY arity dispatcher

`for (arity = 0..MAX) indirectCallArities.add(arity)`, so zlib carries `_invoke_5/7/9/11`
for arities nothing funcalls. On the pre-329 class the ladder family was 12,432 B, 14.7%
of it -- the single biggest machinery item left. todo 323/328 narrowed WHO enters the
ladder, never what it costs to emit. The question here is whether the set can be the
arities the program actually reaches (the `_eval` interpreter can only apply what the
registry holds) rather than the closed range.

## Watch

- Measure the row, do not extrapolate a single-construct probe. Against a
  `(print (+ 1 2))` = 423 B baseline the probes read `apply` +10.8 KB and `boundp`
  +20.2 KB, and on zlib they overlap almost completely -- which is exactly how todo 329
  ended up delivering its win from the tree-shaker instead of the gate.
- A program is verified only on all four backends. zlib needs
  `-W exceptions=y` on both wasm runs; gunzip a fixture and compare byte for byte.
