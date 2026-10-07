# e04. Interpreter `streamp` misses a composite stream built in the same top-level form

Difficulty: Medium

```lisp
(print (streamp (make-two-way-stream (make-string-input-stream "x") (make-string-output-stream))))
```

Measured 2026-10-07: SBCL 2.2.9, JVM, P1 and component `T`; interpreter `NIL`. The same for
`make-echo-stream`, `make-broadcast-stream`, `make-concatenated-stream`. Once a composite stream
was built by an earlier top-level form (or a Gray class was defined), the interpreter answers `T`.

The interpreter's `streamp` call form expands through `LispMacroExpander.expandStreamp`
(`LispEvaluator`, `case LispNames.STREAMP`), which bakes
`closRegistry.descendantTags(fundamental-stream)` at expansion time. The composite classes
(`%two-way-stream` ...) are prelude Gray classes that load when the constructor first RUNS,
after the enclosing form was expanded, so their tags are missing from the baked set. The
function value (`LispEvaluator`'s `streamp` wrap) reads the tags at call time and answers `T`.
`(typep x 'stream)` lowers to `streamp`, so it has the same hole.

## Plan

- Four-backend fixture first (each composite constructor inside the `streamp` / `typep` form).
- Seed the composite classes before the expansion bakes the tags (as `ensure...ClassesFor` does
  for other lazily loaded classes), or let the interpreter's expansion test the tag set at call
  time.
