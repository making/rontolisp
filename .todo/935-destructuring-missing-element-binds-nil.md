# A missing element in a destructuring pattern binds nil instead of signalling

Difficulty: Medium (the same binder as `.todo/928`, but the blast radius is every macro
call that relies on a short argument list)

Split out of `.todo/928` (2026-09-22), which made a SURPLUS element a `program-error`
(`.kb/lambda-lists.md`, "A surplus element in a destructuring pattern"). The other half is
still lenient on every backend:

```lisp
(destructuring-bind (a b) '(1) (list a b))        ; => (1 NIL), SBCL signals
(defmacro m (a (b c) &optional d) ...) (m 1 (2))  ; binds c to nil, SBCL signals
```

- The check belongs beside `LambdaLists.destructuringSurplusCheck`: a required element is
  missing when the list runs out before it (`(consp <cdr chain>)` per level), and on the
  same message rail (`%arity-surplus-message` has a twin shape in `ClosRegistry.arityMessage`
  -- reuse the JVM `_arityMsg` family rather than spelling the count in Lisp).
- Also `(destructuring-bind (a) 5 ...)`: `car` of a non-list; check what each backend does.
- Blast radius first, exactly as 928 did: the whole ANSI suite by test NAME and the full
  `./mvnw test` corpus (every spliced library's `defmacro`). A library relying on a missing
  argument binding nil is a finding to record, not to paper over.
- Sizes: programs that do not destructure must stay byte-identical.
