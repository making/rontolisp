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
collapse whole.

### Two width mechanics that are easy to get wrong

1. **Closing parens are part of the width.** `render` threads a `closers` count:
   how many `)` will be written directly after this node. A form that ends a form
   that ends a form pays for three characters it never emits itself. Omit this and
   the formatter produces lines one or two columns over the margin, everywhere,
   with no obvious cause.
2. **Every "move it elsewhere" rule is written as "would moving it help".**
   `argumentColumn` abandons align-under-first-argument only when the alignment
   column cannot hold the arguments AND `indent + 4` can; `movesToOwnLine` moves a
   distinguished argument down only when it does not fit where it is and does fit
   there. Unconditional versions of either rule make the common case worse.
   `movesToOwnLine` additionally refuses index 1: the first distinguished argument
   sits directly after the operator, already as far left as the form allows, so
   moving it buys at most the operator's width -- it would break up
   `(let* (BINDINGS) ...)` to gain one column.

### Unknown operators are guessed from their name

`IndentRules.byNamingConvention`: `with-*`/`do-*` take one argument then a body,
`without-*` a body, `def*` a name then a body, anything else is a call. This is
not cosmetic. A body-taking macro laid out as a CALL aligns its whole body under
its first argument, so one `usocket:with-server-socket` pushes everything inside
it thirty columns right and every line of it past the margin.

`def*` deliberately guesses ONE distinguished argument, not two, even though
`defun`-shaped macros want two: nothing distinguishes them structurally
(alexandria's `(deftest NAME form... values)` puts a form exactly where `defun`
puts its lambda list), and guessing two pulls a body form up onto the header line.
Guessing one is the harmless half of the choice.

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
neither a comment nor a trailing comment", compared against the same count in the
input.
