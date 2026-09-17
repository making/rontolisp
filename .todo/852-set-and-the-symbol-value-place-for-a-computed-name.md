# Common Lisp: `set` and `(setf (symbol-value name))` for a computed name are missing

Difficulty: Medium

Found while giving Scheme its `eval` (`.todo/833`): a run-time evaluator that wants to
DEFINE or ASSIGN a program global by a computed name has no way to on any backend. The
interpreter answers `The function SET is undefined`, both compile paths refuse
`setf does not support place: SYMBOL-VALUE` (measured 2026-09-17), while `boundp`,
`symbol-value`, `fboundp` and `symbol-function` with a computed name all work on all
four (the compiled name registry and the `_genv` mirror, `.kb/eval-runtime.md`). So
Scheme's `eval` keeps a table of its own for what it defines and for a `set!` of a
program variable (`.kb/scheme-frontend.md`, "`eval`"): a `define` inside `eval` reaches
later `eval`s only, never the program, on every backend alike.

- `set` and the `symbol-value` place are standard Common Lisp; the checklist is
  `.kb/adding-primitives.md` (the interpreter's global environment, the JVM and WASM
  symbol-API compilers, a wrapper, a `ci-spec.yaml` case, the reference docs).
- On the compile path a store into a global that HAS a compiled backing store must write
  that store and not only the `_genv` mirror (the mirror is one-way today, the compiled
  `eval`'s documented limitation, `doc/en/guides/eval-limitations.md`); a name with no
  backing store goes to `_genv`, where `symbol-value` already reads it. The soundness
  gate of `.kb/compile-time-boundp.md` gains an arm: a program calling `set` can make a
  global appear at run time.
- Then `%scheme-eval-define` / `%scheme-eval-assign` in
  `src/main/resources/am/ik/rontolisp/eval/scheme.lisp` can reach the program's globals
  and the "eval's own copy" deviation in `doc/en/guides/scheme.md` goes.
