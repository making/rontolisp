# d01. A literal `boundp` answered by its variable still carries the eval runtime

Difficulty: Medium

```lisp
(defvar *x*)
(defun p () (boundp '*x*))
(print (if (let ((*x* 1)) (p)) 1 0))
```

`(boundp '*x*)` compiles to `(%special-boundp '*x*)`, which reads the variable and never the eval
mirror (`.kb/dynamic-special-variables.md`, "Bound-ness of a special without a value"), yet
`boundp` is still an arm of the `usesEval` OR-chain (`programUsesSymbol(program, BOUNDP)` in both
compilers), so the program carries the eval runtime and its mirror writes. Measured 2026-10-04
against the same program with `(if *x* t nil)` in place of the probe: JVM 10,101 vs 6,491 B (the
printer and array helpers the eval runtime keeps live), wasm 1,090 vs 772 B. That is the cost of
the `(defvar *request*)` idiom on every program that has no other use for the runtime.

Plan: decide the tracked set (`SpecialVarCollector.collectProbedValueless` within the dynamically
bound specials) before the gate, and drop the `boundp` arm when every `boundp` site is a literal
probe of a tracked special; a computed site, or a literal probe of any other name, still needs the
mirror. The ordering is the work: the gate is decided before the injected runtime that the
dynamically-bound collection walks exists. Measure size-report, bench-report and examples for
byte identity.
