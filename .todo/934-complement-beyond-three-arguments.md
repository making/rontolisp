# `complement` refuses a fourth argument

Difficulty: Medium (the unbounded lowering needs `apply`, a gate with a measured WASM cost)

Split out of `.todo/928` (2026-09-22). `expandComplement`'s lambda covers arities 0-3
behind `&optional` supplied-p flags; since `.todo/921` a fourth argument is a
`program-error` instead of being dropped, so `(funcall (complement #'<) 1 2 3 4)` errors
where SBCL answers NIL. ANSI `COMPLEMENT.4` (`(apply (complement #'char=) x)` up to 256
arguments) is ERROR, as are `COMPLEMENT.3` and `.8` (check why).

Not cheap: the only unbounded lowering is `(apply f args)`, and
`.kb/sequence-designator-evaluation.md` ("What a variadic complement costs") measured the
apply tier at +8,313 B (+40%) on WASM for a program that spells `complement`, plus a cons
per call of a complemented `:test-not`. Candidate: keep the 0-3 arms and add an `&rest`
arm that only then applies -- measure whether the arm alone opens the gate
(`needsApplyRuntime`) for every `complement` program, and keep
`twoArgumentComplement`'s known-arity sites untouched.
