# c78. Dynamic-first `symbol-value` inlines an `equal` chain over every special at each site

Difficulty: Medium

In a program that uses `progv`, every `(symbol-value x)` site expands through
`LispMacroExpander.dynamicFirstSymbolValue` into an inline `(if (equal n 'S) S ...)` chain over
the WHOLE special set, on the JVM and both wasm backends. Its size is O(specials) per site.

Measured 2026-10-04 on the ci-spec shared program (223 `defvar`/`defparameter`): one site costs
~30 KB of JVM bytecode. A defun with two sites was 59,571 bytes (the 65,535 limit is near), and a
top-level form with two sites overflowed `_top$55` (83,595 bytes), so the ci-spec case
`top-level-forms-answer-like-defun-bodies` keeps one site per form.

Plan:

1. A LITERAL quoted name (`(symbol-value '*x*)`) folds at compile time: the variable read when
   it names a special, the raw mirror probe otherwise. No chain.
2. A computed name calls ONE shared runtime (a generated defun over the special set) instead of
   an inline chain per site.
3. Pin the per-site size (JVM method size, wasm body size) in the backend tests; measure the
   size-report/bench/examples change.
