# h46. `loop` with `while` BEFORE a `for` runs that `for`'s step before the test

Difficulty: Medium

Found 2026-10-30 probing `cl-markup`: `(markup (:ul (:li "a")))` loses its closing
`</ul>`. Its `map-group-if` (`src/util.lisp:12`) is

```lisp
(loop with acc = (list cur) while list for x = (pop list) ...)
```

```lisp
(let ((list (list 1 2 3))) (loop while list for x = (pop list) collect x))
;; SBCL: (1 2 3)   here: (1 2)
```

`.kb/loop-iteration-heads.md`: clause heads are ANDed "in SOURCE order", but a
`while`/`until` that precedes a `for` is evaluated after that `for`'s assignment. The last
element is popped and then the loop ends. CLHS says a `while` before a `for` is
nonconforming, but SBCL, CCL and real libraries keep source order. Shared expander, so this
affects all four backends. Add the failing case first.
