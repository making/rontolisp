# loop

`(loop clause...)` (extended) or `(loop form...)` (simple)

A bounded subset of the ANSI `loop` macro. It expands to the existing iteration core (`do*`-style stepping wrapped in the internal block boundary), so it works identically on the interpreter and both compilers.

If every top-level subform is a compound form, `loop` is a **simple loop**: it repeats those forms forever until a `return` exits it.

```lisp
(let ((i 0))
  (loop
    (setq i (+ i 1))
    (when (= i 5) (return i)))) ; => 5
```

Otherwise it is an **extended loop** built from clauses. The supported clauses are:

- Numeric stepping: `for VAR from LO [to|upto|below|downto|above HI] [by STEP]` (also `upfrom`/`downfrom`; a limit keyword with no `from` starts at 0).
- List stepping: `for VAR in LIST [by FN]` and `for VAR on LIST [by FN]`.
- String stepping: `for VAR across STRING` binds `VAR` to each character in turn.
- General stepping: `for VAR = INIT [then STEP]`.
- Local variables: `with VAR [= INIT]` (chainable with `and`).
- Accumulation: `collect`, `append`, `nconc`, `sum`, `count`, `maximize`, `minimize`, each with an optional `into VAR`.
- Control: `while`/`until`, `repeat N`, `do FORM...`, `return EXPR`, `initially FORM...`, `finally FORM...`, and the conditionals `when`/`if`/`unless` with optional `else` and `end`.

Multiple `for` clauses step in parallel, so the loop ends as soon as the shortest driver is exhausted — the idiomatic indexed map:

```lisp
(loop for x in '(a b c) for i from 0 collect (list i x)) ; => ((0 a) (1 b) (2 c))
```

Accumulation and numeric ranges cover the common cases directly:

```lisp
(loop for i from 1 to 10 when (evenp i) sum i) ; => 30
```

`for ... across` walks a string character by character (strings are the only random-access sequence type here):

```lisp
(loop for c across "hello" count (eql c #\l)) ; => 2
```

Limitations (out of scope for this first cut): destructuring binds, parallel `and` between `for` clauses, `being`, the anaphoric `it`, `named`/`loop-finish`, and `thereis`/`always`/`never`. `while`/`until` terminate at the top of the iteration regardless of their textual position. Accumulation clauses without `into` must all be of the same kind; collecting clauses build the result list in source order.
