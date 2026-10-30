# A literal `format` iteration body reads past its list as nil

Difficulty: Medium

A run-time control signals where a `~{ ... ~}` body reads past the end of its list, as SBCL
does. The same control written as a literal renders the missing element as nil:

```console
$ rontolisp -e '(print (format nil "~{~a ~a~}" (list 1)))'
"1 NIL"
```

SBCL: `error in FORMAT: No more arguments`. Same for `~:{~a ~a~}` over `((1))`, `~{~a~*~}`, and
the forced `~:{~a~:}` / `~{~a~:}` over nil (the literal path renders `""`, skipping the forced
pass; SBCL runs it and signals).

Cause: `LispMacroExpander.carChain` binds the item temporaries with `(car (nthcdr k base))`, and
`sublistLoopExpr` / `flatLoopExpr` never check the tail. `FmtArgs.forRuntimeItems` has no bound.

Direction: check the tail only where it can be short (items 1.. of a flat pass, every item of a
sublist pass, the forced pass), signalling the literal path's text
(`format: not enough arguments for directive ~a`). Weigh what the check adds to every program
whose literal `~{` consumes two or more items against the gain; record the measurement in
`.kb/format.md`. Then drop the doc bullet in `doc/{en,ja}/reference/macros/format.md` and add the
literal rows to ci-spec `format-runtime-control-signals-a-missing-argument`.
