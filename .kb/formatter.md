# The Source Formatter (`rontolisp format`)

`am.ik.rontolisp.format` re-indents Lisp source. The invariant it exists to
uphold, and the only one that may never bend:

> **Formatting changes whitespace and nothing else.** The token stream of the
> output is identical to the token stream of the input, and formatting is a
> fixpoint. Both are pinned by `LispFormatterTest`, over every `.lisp` and `.asd`
> file in the repository (~370 of them, most written by other people for other
> implementations: cl-ppcre, ironclad, esrap, trivia, sxql, alexandria).

`FormatCommand` (in `cli`) is the file plumbing: directory walk, in-place write,
`--check` / `--stdout` / `--width=N`, exit codes 0/1/2. Everything below is the
`format` package.

## Why it does not use the reader

`LispReader` is the wrong front end for a formatter, and not by a little. It
upcases symbol names, folds `1,000` to `1000`, rewrites `'x` into `(quote x)`,
EVALUATES `#+`/`#-` guards against the active feature set (dropping the
non-matching branch outright), rejects `#.`, and discards every comment. Each of
those is a fact about the SOURCE that the output has to reproduce.

So the package has its own front end, `FormatReader` -> `CstNode`:

- `Atom` -- one token, verbatim: symbols with their package prefix / `\` escapes /
  `|...|` runs, numbers in whatever radix or grouping they were typed, string
  literals including quotes and escapes, `#\c`, `#*1010`, `#P"..."`, `#3#`, and a
  lone `.`.
- `Listing` -- `(`, `#(`, `#S(`, `#2A(`, `#f(`, `#8@(` ... always closed by `)`,
  so only the opener is stored.
- `Prefix` -- `'`, `` ` ``, `,`, `,@`, `#'`, `#.`, `#3=`, and the `#+feature` /
  `#-feature` guards, whose feature expression is folded into the prefix text with
  its whitespace collapsed.
- `LineComment` / `BlockComment` -- comments are nodes, not trivia.

`FormatReader` accepts the same surface syntax as `LispLexer` (including the
`1,000` grouping comma, which is otherwise a terminating character) but never
interprets it -- it only finds token boundaries. **If you extend the reader's
surface syntax, extend `FormatReader.readDispatch`/`readToken` too**; the corpus
test is what will tell you, since an unknown `#X(` form reads as the atom `#X`
followed by a separate list and the token comparison then fails.

The only thing a node keeps of the original layout is `Trivia`: two bits,
`blankLineBefore` and `startsLine`. Everything else about the whitespace is
discarded and re-derived.

## The layout model

`IndentRules` maps an operator to a `Style`; `LispFormatter` walks the tree
emitting text and tracking the current column.

A `:key value` pair is one unit everywhere, not only in call position -- a
`defsystem`'s tail is a BODY of options, and pairing it in `renderCall` alone split
every keyword from its value. A pair takes a line of its own (a column of options
reads better than a paragraph of them), and its value stays beside the key: even a
value far too wide for any line belongs there, wrapping under its own first
element, rather than stranded below its key. The exception is depth --
`renderPairValue` sends a wrapping value below its key once the key's line has less
room left than a fresh line would give, or a nested
`:components ((:module ... :components (...)))` walks the whole tree rightward.

A `Style` is `(kind, inlineArgs, bodyIndent, childStyle, statements)`:

- `CALL` -- arguments align under the first one; trailing `:key value` pairs are
  grouped. `DATA` -- no operator, elements align just inside the delimiter.
  `BODY`/`CLAUSES` -- `inlineArgs` arguments on the operator's line, the rest at
  `bodyIndent`. `DO`, `LOOP`, `DEFMETHOD` are three shapes that need their own
  code.
- `childStyle` is the load-bearing one. Structurally `(rec (list acc) body)` is
  indistinguishable from a function call, and `(t (foo) (bar))` from a call to
  `t`; only the ENCLOSING operator knows they are a local-function definition and
  a `cond` clause. `childStyle` is how `labels` and `cond` say so.
- `statements` says the trailing arguments are an implicit `progn`. Two or more of
  those get a line each however short they are -- the Lisp reading of why no
  formatter of a C-like language writes `if (x) { a(); b(); }`. It is FALSE
  wherever the tail is alternatives or options instead (`if`, `cond`'s clauses,
  `defvar`'s value and docstring, `defstruct`'s slots, a `let` binding's init
  form), which stay on one line while they fit.

`statements` is also checked inside `flat()`, not only at the top of each form: a
form that must break has NO one-line rendering at all, or an enclosing form that
does fit would flatten it from above and `(defun f (x) (when x (a) (b)))` would
collapse whole. `do`/`do*` is the one form that breaks on a body of ONE: its three
parts are told apart by the layout and nothing else, so a one-line `do` loop hides
which of them is which.

### Two width mechanics that are easy to get wrong

1. **Closing parens are part of the width.** `render` threads a `closers` count:
   how many `)` will be written directly after this node. A form that ends a form
   that ends a form pays for three characters it never emits itself. Omit this and
   the formatter produces lines one or two columns over the margin, everywhere,
   with no obvious cause.
2. **Every "move it elsewhere" rule is written as "would moving it help".**
   `movesToOwnLine` moves a distinguished argument down only when it does not fit
   where it is and does fit there; unconditionally, it would break up
   `(let* (BINDINGS) ...)` to gain one column, which is also why it refuses index 1
   (the first distinguished argument sits directly after the operator, already as
   far left as the form allows).

   Align-under-first-argument is the one that needs watching, because getting it
   wrong COMPOUNDS: every call nested in a too-deep argument repeats the mistake one
   level further right, and a four-deep chain walks off the page. `argumentColumn`
   therefore gives the alignment up for `indent + 1` whenever the arguments do not
   fit at it, and never charges depth for an alignment with nothing to align --
   a call with ONE argument that must break starts it at `indent + 1`. An argument
   that fits in NEITHER column is left out of that measurement, since it wraps in
   any column and so cannot speak to which column the others should get. Getting
   these three right cut the corpus's over-margin lines by more than half.

   Being left out of the measurement is not the same as having no say, and reading
   it as the same was a bug: at two identical calls one nesting level apart, the
   `(format nil "...")` argument fit at `indent + 1` in the shallower one and voted
   for it, fit nowhere in the deeper one and was skipped, and the deeper site kept
   an alignment that ran 15 columns past the margin -- chosen by the one argument
   (`400`) with room to spare. An argument that fits nowhere therefore also votes
   for `indent + 1`, but only for itself and only on the same "would moving it
   help" terms as `movesToOwnLine`: when the ALIGNMENT is what puts it over and
   `indent + 1` takes it back under. That question is `narrowest`, which measures
   how wide a node still is once broken as far as it can be -- an unbreakable
   54-column string is over from a deep column however it breaks, while a
   `(t (prog1 (schar ...) (incf ...)))` cond clause is only too wide FLAT and is
   comfortable in either column once it breaks, so it keeps its siblings' alignment.
   `narrowest` returns TWO widths, widest line and last line, because the closing
   parens that follow a form land on its last line only; charging them to its widest
   line makes it look two columns wider than it is and costs a `cond` its alignment.
   This took the corpus measurement below from 481 to 414.

### Unknown operators are guessed from their name

`IndentRules.byNamingConvention`: `with-*`/`do-*` take one argument then a body,
`without-*` a body, `def*` a name and (if it is written with one) a lambda list
then a body, anything else is a call. This is not cosmetic. A body-taking macro
laid out as a CALL aligns its whole body under its first argument, so one
`usocket:with-server-socket` pushes everything inside it thirty columns right and
every line of it past the margin.

`def*` is the one guess that is not made from the name alone, because the name does
not carry the answer. A definition macro has two shapes -- `(define-get "/x" (req)
BODY)` wants `defun`'s two distinguished arguments, alexandria's `(deftest NAME
form... values)` wants one, and the operator is spelled the same way in both. So
`isDefunShaped` reads the number off the FORM: a name (an atom), then a second
element that could be a lambda list, then at least one more element. Both halves of
getting it wrong are real -- at one, a lambda list becomes a body form and a body of
two never shares a line, so `(define-get "/hello" () (ok "hello world"))` took three
lines to say 43 columns; at two, a body form is pulled up beside the operator and the
rest of the body aligns under it.

"Could be a lambda list" is a negative test (`isLambdaList`), since nothing ever
says positively that `(req)` is one rather than a call: every element must be
something a lambda list is allowed to hold. A string, a number, a keyword or a nested
form rules it out -- which is what keeps split-sequence's `(define-test NAME (:input
...) ...)` and tiny-routes' `(define-routes *app* (define-get ...) ...)` at one
argument -- and so, decisively, do **`nil` and `t`**: a lambda list may not bind a
constant. That last clause is what tells alexandria's `(deftest xor.3 (xor nil nil
nil) nil t)` from a definition written in the same bare symbols, and it is the reason
the rule can cover the whole `def*` family instead of only the hyphenated
`define-*` half.

What remains ambiguous is decided in favour of the lambda list: a `(deftest NAME (foo
bar) ...)` whose second element is a two-symbol CALL is laid out as a header. No such
form exists in the corpus, but the reading is a guess and not a deduction -- an
operator that matters belongs in `RULES`. The rule moved 90 forms in 18 files when it
landed (43 `defpattern`, 17 `define-modify-macro`, 10 `define-get`, ...), every one of
them a genuine definition that had been splitting its lambda list off; the
over-margin count below did not move.

## Deliberate divergences from trivial-formatter

The design reference was [trivial-formatter](https://github.com/hyotang666/trivial-formatter).
Three differences, each with its reason, so the next visitor can tell whether the
reason still holds:

1. **No loading.** trivial-formatter must `asdf:load-system` a system before it can
   print it, because it delegates indentation to the host's pprint dispatch and so
   needs the macros to exist. Here the table is static and formatting is purely
   syntactic: a file formats without its dependencies, and without its
   `defpackage` having been evaluated. The cost is that a macro's real shape is
   never consulted -- hence the naming-convention guess above.
2. **Blank lines are preserved** (collapsed to one), wherever they are. It drops
   them inside a form and forces exactly one between top-level forms. A blank line
   is the only paragraph break Lisp source has, and re-deciding it is not a layout
   question a formatter can answer.
3. **Token case is preserved.** It downcases everything (`*print-case* :downcase`),
   because CL's printer forces a choice; nothing forces one here, and rewriting an
   author's spelling is a change to the source that buys nothing.

Trailing-comment alignment across consecutive lines is an ADDITION, taken from
gofmt: hand-written alignment is real information that cannot survive
re-indentation, so the formatter re-establishes it instead of preserving it. It
runs as a post-pass (`alignTrailingComments`) because the target column is not
known until the whole run has been written.

## Known limit

Line comments and string literals are never re-wrapped -- their content is the
author's. A line whose content cannot be broken may therefore exceed the margin,
and deeply nested expressions can too. The margin is a target, not a guarantee;
`LispFormatterTest` pins the token stream and the fixpoint, NOT the margin. If you
tighten this, the useful measurement is "output lines over the margin that are
neither a comment nor a trailing comment", taken over the files
`LispFormatterTest.repositoryLispSources` walks and compared against the same count
in the input:

```
files=377  code lines over 80: source=1365 formatted=414  files-made-worse=5
```

Only the `formatted` column is comparable across sessions. The `source` column
measures the input, and the input moves whenever `examples/` or
`src/main/resources/` is reformatted -- which every rule change has to do anyway, or
`rontolisp format --check` over them goes red.
