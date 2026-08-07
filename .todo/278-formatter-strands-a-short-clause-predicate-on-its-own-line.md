# `rontolisp format` strands a short clause predicate on a line of its own

Difficulty: Low

A `cond`/`case`/`typecase` clause whose predicate is a one-token atom loses a whole
line to that token when the clause has to break:

```lisp
;; rontolisp format
(t
 (json-response 404
                (rontolisp:plist-hash-table (list :error "not found" :path path))))

;; hand-written (and what Emacs' align-under-first-element gives)
(t (json-response 404
                  (rontolisp:plist-hash-table (list :error "not found" :path path))))
```

Seen twice in one file while formatting `examples/net/httpbin-clack.lisp` (commit
`80c70d57`), so it is not rare -- `(t ...)` and `(otherwise ...)` are the default
clause of nearly every `cond` and `case`.

## Why it happens

`IndentRules.CLAUSE` is `Style.body(0, 1)`: zero arguments on the predicate's line,
every body form at one column past the clause's paren. Zero is the right answer for
a LIST predicate, and deliberately so --

```lisp
((funcall less (car a) (car b))
 (cons (car a) (merge2 less (cdr a) b)))
```

reads far better than the alternative, which would break inside the first body form
to keep it beside a 30-column predicate. The rule is only wrong when the predicate
is so narrow that its line has room to spare.

## The fix

Give `Style.Kind.CLAUSES` a dynamic `inlineArgs` the way `DEFMETHOD` already has one
(`LispFormatter.lambdaListIndex` is the precedent): **1 when the predicate is an Atom
AND the clause has exactly one body form, 0 otherwise.**

Both halves of that condition are load-bearing. Without "predicate is an Atom", a
long list predicate would drag its body up beside it. Without "exactly one body
form", a multi-form clause would put its first body form in one column and the rest
in another:

```lisp
(integer (foo x)
 (bar x))          ; <- what inlineArgs = 1 does to two body forms
```

## Non-goals

Nothing else about clause layout. In particular `cond`'s own style
(`Style.call(CLAUSE)` -- clauses aligned under the first clause rather than at a body
indent) is settled and covered by `alignsCondClausesUnderTheFirstOne`.

## Done when

- `LispFormatterTest` has a fixture for each half of the condition: an atom predicate
  with one body form keeps it on its line; an atom predicate with two body forms, and
  a list predicate with one, do not.
- The whole-corpus assertions still hold (identical token stream + fixpoint over every
  checked-in `.lisp`/`.asd`), and `rontolisp format --check examples/
  src/main/resources/` is re-run and its result committed -- the tree is formatted as
  of `80c70d57`, so leaving it unformatted after a rule change would break the gate.
- The over-margin measurement in `.kb/formatter.md` ("1111 -> 481") is re-taken if it
  moves.
