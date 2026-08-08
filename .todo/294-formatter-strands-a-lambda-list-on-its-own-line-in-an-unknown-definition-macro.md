# `rontolisp format` strands a lambda list on its own line in an unknown definition macro

Difficulty: Medium

A `def`-prefixed macro the indent table does not know gets ONE distinguished
argument, so when its second element is a LAMBDA LIST that lambda list becomes a
body statement and takes a whole line:

```lisp
;; rontolisp format -- tiny-routes' route macros, and this is a fixpoint
(define-get "/hello"
  ()
  (ok "hello world"))
(define-get "/users/:id"
  (req)
  (ok (format nil "user ~A" (path-parameter req :id))))

;; what the same shape gets when the table DOES know it (defun)
(defun hello (req)
  (ok "hello world"))
```

Found 2026-08-08 writing `examples/asdf/tiny-routes-demo.lisp`: six of the
demo's seven routes render that way, and the file is checked in like that
because it IS the formatter's answer. A one-line
`(define-get "/hello" () (ok "hello world"))` is 43 columns and would fit
anywhere.

## Why it happens, and what is NOT the bug

`IndentRules.byNamingConvention` maps a `def*`/`define-*` name to
`Style.body(1, 2)`, with the reason spelled out in its comment: a definition
macro whose second element is a lambda list would read better with TWO
distinguished arguments, but nothing tells the two apart, and guessing two
pulls a body FORM up onto the header line when the second element is a form.

The "two statements never share a line" rule that turns the lambda list into
its own line is deliberate and settled (`.kb/formatter.md`: *"Two or more of
those get a line each however short they are -- the Lisp reading of why no
formatter of a C-like language writes `if (x) { a(); b(); }`"*). It is why
`(when a (foo) (bar))` breaks too, and it is NOT what to change here. The
lambda list is simply not a statement, and the guess is what misfiles it as
one.

## The corpus says both shapes are real

A regex survey of every `.lisp` in the repository, over occurrences of a
`def*`/`define-*` operator the table does not know (2026-08-08; 502 matches, 35
distinct names -- approximate, the pattern also catches a few non-operator
`(default ...)`-shaped lists, so re-measure before relying on an exact figure):

| shape of the second element | count | examples |
|---|---|---|
| a FORM or an options list -- `body(1, 2)` is right | ~330 | alexandria `deftest NAME (let ...)` (246), split-sequence `define-test NAME (:input ...)` (80) |
| unambiguously a LAMBDA LIST (`()`, `nil`, or one starting with `&key`/`&rest`/...) | 22 | trivia `defpattern array (&key ...)` (17), tiny-routes `define-get "/x" ()` (5) |
| a list of bare symbols -- ambiguous with a call | 43 | trivia `defpattern cons (a b)` (26), tiny-routes `define-get "/x" (req)` (3) |

So the counter-example the current comment cites is the MAJORITY shape and must
keep working; the fix has to be a discriminator, never a flip of the default.
Most of what the three rows do not cover is the spec-list shape (`sxql`'s
`(define-op (:not unary-op))`, 32), which `body(1, 2)` already lays out
correctly.

## Directions, none decided

1. **The unambiguous half only.** A second element that is `()`/`nil`, or a
   list containing a lambda-list keyword (`&optional`/`&rest`/`&key`/`&body`/
   `&aux`), is a lambda list and cannot be a form -- an empty call does not
   exist. That is 22 of the 65 lambda-list occurrences and, notably, the exact
   `(define-get "/x" () ...)` shape. Cheap, provably safe, incomplete.
2. **Widen to a list of bare non-keyword symbols.** Picks up the other 43, and
   misfires on any `(deftest NAME (foo bar))` whose second element is a
   two-symbol CALL. Needs a measurement of how many of those exist before it
   can be judged.
3. **Out-of-band metadata**, which is what the ecosystem actually does: Emacs
   carries `lisp-indent-function` / `common-lisp-indent-function` properties and
   trivial-formatter delegates to the host's pretty-print dispatch after LOADING
   the system. rontolisp's formatter deliberately loads nothing
   (`.kb/formatter.md` -- that is the one place it differs in KIND), so the
   equivalent would be a project-level config file the formatter reads
   (operator -> style), which also gives every other unknown macro an escape
   hatch. Bigger, and it introduces a file format.

## Non-goals

- **Adding third-party operator names to `IndentRules`.** Considered and
  rejected in the tiny-routes pass: the table is documented as "the whole of the
  formatter's knowledge about the LANGUAGE", and a list of library APIs is
  unbounded. If direction 3 is taken, the per-project config is where such names
  belong.
- The `statements` rule itself (see above), `cond`'s clause style, and
  `defmethod`'s dedicated shape.

## Done when

- `LispFormatterTest` has a fixture per accepted discriminator AND a fixture for
  the counter-example that must NOT flip (`deftest NAME (let ...)` stays at
  `body(1, 2)`).
- The whole-corpus assertions still hold (identical token stream + fixpoint over
  every checked-in `.lisp`/`.asd`), and the blast radius is MEASURED rather than
  assumed: reformat the corpus with and without the change and diff, then say in
  the commit how many files and forms moved.
- `examples/asdf/tiny-routes-demo.lisp` is re-formatted and re-run on all four
  backends (its output is pinned in `examples/asdf/README.md`), and any other
  checked-in file the rule moves is re-formatted in the same commit.
- `.kb/formatter.md`'s "naming-convention guess for unknown macros" section
  records the new rule AND why the rejected half was rejected -- a discriminator
  with only a "how" is the same permanent gap the current one-argument guess is.
