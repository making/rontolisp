# The Source Formatter (`rontolisp format`)

`am.ik.rontolisp.format` re-indents Lisp source.

> **Invariant: formatting changes whitespace and nothing else.** The output's token
> stream equals the input's, and formatting is a fixpoint. Both pinned by
> `LispFormatterTest` over every `.lisp`/`.asd` file in the repo (~370, most written by
> other people for other implementations: cl-ppcre, ironclad, esrap, trivia, sxql,
> alexandria).

`FormatCommand` (in `cli`) is the file plumbing: directory walk, in-place write,
`--check` / `--stdout` / `--width=N`, exit codes 0/1/2. Everything else is the `format`
package.

**Whitespace is not always removable.** A `CstNode.Prefix` prints GLUED to its datum,
right for `'`/`` ` ``/`#'` and wrong for exactly one case: a `,` whose datum starts with
`@` or `.`, since `, @x` glued is `,@x`, a different token. `FormatReader.separatedPrefix`
keeps a single space there and only there (found via trivial-utf-8's `(, @manual)`).
General rule for a future prefix: the space may be dropped only when gluing cannot make a
longer reader macro.

## Own front end, not `LispReader`

`LispReader` upcases symbol names, folds `1,000` to `1000`, rewrites `'x` into
`(quote x)`, EVALUATES `#+`/`#-` against the active feature set, rejects `#.`, and
discards comments — each a fact about the SOURCE the output must reproduce.

`FormatReader` -> `CstNode`:
- `Atom` — one token verbatim: symbols with package prefix / `\` escapes / `|...|`,
  numbers in any radix or grouping, string literals with quotes and escapes, `#\c`,
  `#*1010`, `#P"..."`, `#3#`, a lone `.`.
- `Listing` — `(`, `#(`, `#S(`, `#2A(`, `#f(`, `#8@(` …, always closed by `)`, so only the
  opener is stored.
- `Prefix` — `'`, `` ` ``, `,`, `,@`, `#'`, `#.`, `#3=`, and `#+feature`/`#-feature`
  guards (feature expression folded into the prefix text, whitespace collapsed).
- `LineComment` / `BlockComment` — comments are nodes, not trivia.

`FormatReader` accepts the same surface syntax as `LispLexer` (including the `1,000`
grouping comma) but never interprets it. **Extending the reader's surface syntax means
extending `FormatReader.readDispatch`/`readToken` too** — otherwise an unknown `#X(` reads
as atom `#X` plus a separate list and the corpus token comparison fails.

A node keeps only `Trivia`: `blankLineBefore` and `startsLine`. All other whitespace is
discarded and re-derived.

**Source reaches `FormatReader` VERBATIM — nothing normalizes line endings first.** A `\r`
inside a string literal (or after `#\`) is data. `skipSpace` is the one place deciding what
a line break is — LF, CRLF and a lone CR, each counted once — and `readLineComment` ends at
CR as well as LF (else a CR-only source folds the file into one comment). Output is LF
where the formatter emits the break; a CR inside a literal or a `#|...|#` block (reproduced
verbatim) stays. Pinned by `keepsACarriageReturnThatIsInsideALiteral` +
`normalizesLineEndingsAndTheFinalNewline`; found via
`src/test/resources/cl-mustache/t/test-spec.lisp`, whose template literals carry real CRLFs.

## Layout model

`IndentRules` maps an operator to a `Style`; `LispFormatter` walks the tree emitting text
and tracking the current column.

A `:key value` pair is one unit EVERYWHERE, not only in call position (a `defsystem` tail
is a BODY of options; pairing only in `renderCall` split every keyword from its value). A
pair takes its own line and its value stays beside the key, even when far too wide,
wrapping under its own first element. Exception is depth: `renderPairValue` sends a
wrapping value below its key once the key's line has less room left than a fresh line
would give — otherwise a nested `:components ((:module ... :components (...)))` walks
rightward.

`Style` is `(kind, inlineArgs, bodyIndent, childStyle, statements)`:
- `CALL` — arguments align under the first; trailing `:key value` pairs grouped.
  `DATA` — no operator, elements align just inside the delimiter. `BODY`/`CLAUSES` —
  `inlineArgs` arguments on the operator's line, rest at `bodyIndent`. `DO`, `LOOP`,
  `DEFMETHOD`, `CLAUSE` need their own code; the last two read `inlineArgs` off the form.
- `childStyle` — structurally `(rec (list acc) body)` is indistinguishable from a call and
  `(t (foo) (bar))` from a call to `t`; only the ENCLOSING operator knows. This is how
  `labels` and `cond` say so.
- `statements` — trailing arguments are an implicit `progn`; two or more get a line each
  however short. FALSE where the tail is alternatives or options (`if`, `cond` clauses,
  `defvar`'s value and docstring, `defstruct` slots, a `let` binding's init form), which
  stay on one line while they fit. It is also checked inside `flat()`, not only at the top
  of a form: a form that must break must have NO one-line rendering, or an enclosing form
  that fits flattens it from above and `(defun f (x) (when x (a) (b)))` collapses whole.
  `do`/`do*` is the one form that breaks on a body of ONE — its three parts are told apart
  by layout alone.

### Width mechanics that are easy to get wrong

1. **Closing parens are part of the width.** `render` threads a `closers` count: how many
   `)` follow this node. Omit it and every line runs one or two columns over the margin
   with no obvious cause.
2. **Every "move it elsewhere" rule is "would moving it help".** `movesToOwnLine` moves a
   distinguished argument down only when it does not fit where it is and does fit there;
   unconditionally it would break `(let* (BINDINGS) ...)` for one column. It refuses
   index 1 (the first distinguished argument already sits as far left as the form allows).
3. **Align-under-first-argument COMPOUNDS** — every call nested in a too-deep argument
   repeats the mistake one level further right. `argumentColumn` gives the alignment up
   for `indent + 1` whenever the arguments do not fit at it, and never charges depth for an
   alignment with nothing to align (a call with ONE argument that must break starts at
   `indent + 1`). An argument fitting in NEITHER column also votes for `indent + 1`, but
   only for itself and only when the ALIGNMENT is what puts it over and `indent + 1` takes
   it back under. That question is `narrowest`, which measures how wide a node still is
   once broken as far as it can be, and returns TWO widths — widest line and last line —
   because trailing closing parens land on the last line only; charging them to the widest
   line costs a `cond` its alignment.

### A clause keeps a single body form beside a bare predicate

A `cond`/`case`/`typecase` clause is `Style.Kind.CLAUSE`; how much stays on the predicate's
line is read off the FORM (`LispFormatter.clauseInlineArgs`), like `defmethod`'s. Zero is
right for a list predicate and wrong for `(t ...)`.

Conditions: the predicate must be an ATOM; the body must be a SINGLE form (splitting a body
of two across two columns is the one thing a body may never do); and WIDTH. No bound on the
predicate's own width works — the lift goes wrong when the body would fit on one line below
anyway (the lift merely breaks it open) or when it COMPOUNDS through nested forms. So both
renderings are measured and the lift is taken only when the body needs no more lines beside
the predicate than below it and runs past the margin no more often (`shapeAt`: writes the
body at a column, looks at the result, unwrites it). `narrowest` is not honest enough here —
it is a LOWER bound charging every element the shallowest offset any style could give it.
The trial is asked only for a clause's single body form at its two candidate columns (~330
decisions over the corpus, no measurable time).

The `do` end-test clause takes the same style (same shape: a test, then result forms at 1).

### Unknown operators are guessed from their name

`IndentRules.byNamingConvention`: `with-*`/`do-*` take one argument then a body,
`without-*` a body, `def*` a name and (if written with one) a lambda list then a body,
anything else is a call. Not cosmetic: a body-taking macro laid out as a CALL aligns its
whole body under its first argument, so one `usocket:with-server-socket` pushes everything
thirty columns right.

The table also carries THIRD-PARTY body-taking operators seen in checked-in `.lisp`: uiop's
`with-deprecation` / `while-collecting`, alexandria's `if-let` / `when-let`, rove's
`testing` / `failing` (description then body), `setup` / `teardown` (body), `defhook` (name
AND phase keyword, then body). rove's `deftest` needs no entry (the `def*` guess reads its
shape), nor do `ok` / `ng` / `signals` (trailing forms are arguments).

`def*` is not guessed from the name: a definition macro has two shapes —
`(define-get "/x" (req) BODY)` wants `defun`'s two distinguished arguments, alexandria's
`(deftest NAME form... values)` wants one. `isDefunShaped` reads the count off the FORM: a
name (atom), a second element that could be a lambda list, then at least one more element.
Both errors are real — at one, a lambda list becomes a body form and a body of two never
shares a line; at two, a body form is pulled up beside the operator.

"Could be a lambda list" is a NEGATIVE test (`isLambdaList`): every element must be
something a lambda list may hold. A string, number, keyword or nested form rules it out
(keeping split-sequence's `(define-test NAME (:input ...) ...)` and tiny-routes'
`(define-routes *app* (define-get ...) ...)` at one argument), and so do **`nil` and `t`** —
a lambda list may not bind a constant, which is what tells alexandria's
`(deftest xor.3 (xor nil nil nil) nil t)` from a definition and lets the rule cover the
whole `def*` family, not just `define-*`.

Ambiguity is decided in favour of the lambda list: `(deftest NAME (foo bar) ...)` with a
two-symbol CALL second element is laid out as a header. That reading is a guess — an
operator that matters belongs in `RULES`.

## Deliberate divergences from trivial-formatter

Design reference: https://github.com/hyotang666/trivial-formatter.

1. **No loading.** trivial-formatter must `asdf:load-system` first because it delegates
   indentation to the host's pprint dispatch. Here the table is static and formatting is
   purely syntactic: a file formats without its dependencies and without its `defpackage`
   evaluated. Cost: a macro's real shape is never consulted — hence the naming-convention
   guess.
2. **Blank lines are preserved** (collapsed to one), wherever they are. It drops them
   inside a form and forces exactly one between top-level forms.
3. **Token case is preserved.** It downcases everything (`*print-case* :downcase`).

Trailing-comment alignment across consecutive lines is an ADDITION taken from gofmt: it
re-establishes rather than preserves, and runs as a post-pass (`alignTrailingComments`)
because the target column is not known until the whole run is written.

## Known limit

Line comments and string literals are never re-wrapped, so a line whose content cannot be
broken may exceed the margin, as can deeply nested expressions. The margin is a target, not
a guarantee; `LispFormatterTest` pins the token stream and the fixpoint, NOT the margin.

To tighten it, measure "output lines over the margin that are neither a comment nor a
trailing comment" over the files `LispFormatterTest.repositoryLispSources` walks, compared
against the same count in the input:

```
files=417  code lines over 80: source=1429 formatted=417  files-made-worse=5
```

Only the `formatted` column is comparable across sessions, and only over the same tree (the
corpus grows). The `source` column moves whenever `examples/` or `src/main/resources/` is
reformatted — which every rule change must do anyway, or `rontolisp format --check` over
them goes red. That check is NOT wired into CI, so the tree drifts as files are added.
