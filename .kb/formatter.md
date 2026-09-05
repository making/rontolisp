# The Source Formatter (`rontolisp format`)

`am.ik.rontolisp.format` re-indents Lisp source. **Invariant: formatting changes whitespace and
nothing else** -- the output's token stream equals the input's, and formatting is a fixpoint.
Both pinned by `LispFormatterTest` over every `.lisp`/`.asd` file in the repo (~370, mostly
third-party). `FormatCommand` (in `cli`) is the file plumbing: directory walk, in-place write,
`--check` / `--stdout` / `--width=N`, exit codes 0/1/2.

## Own front end, not `LispReader`
`LispReader` upcases, folds `1,000`, rewrites `'x`, EVALUATES `#+`/`#-`, rejects `#.` and
discards comments -- each a fact about the SOURCE the output must reproduce. `FormatReader`
produces `CstNode`: `Atom` (one token verbatim), `Listing` (`(`, `#(`, `#S(`, `#2A(`, `#8@(` ...
-- only the opener is stored), `Prefix` (`'`, `` ` ``, `,`, `,@`, `#'`, `#.`, `#3=`, and
`#+feature`/`#-feature` guards), `LineComment`/`BlockComment` (nodes, not trivia). A node keeps
only `Trivia`: `blankLineBefore`, `startsLine`; all other whitespace is re-derived.

- **Extending the reader's surface syntax means extending `FormatReader.readDispatch`/`readToken`
  too** -- otherwise an unknown `#X(` reads as atom `#X` plus a list and the corpus token
  comparison fails.
- **Whitespace is not always removable.** A `Prefix` prints GLUED to its datum, wrong for exactly
  one case: a `,` whose datum starts with `@` or `.`, since `, @x` glued is `,@x`.
  `FormatReader.separatedPrefix` keeps one space there and only there. General rule for a future
  prefix: drop the space only when gluing cannot make a longer reader macro.
- **Source reaches `FormatReader` VERBATIM** -- nothing normalizes line endings first; a `\r`
  inside a string literal or after `#\` is data. `skipSpace` is the one place deciding what a line
  break is (LF, CRLF, lone CR, each counted once) and `readLineComment` ends at CR as well as LF.
  Pinned by `keepsACarriageReturnThatIsInsideALiteral`, `normalizesLineEndingsAndTheFinalNewline`.

## Layout model
`IndentRules` maps an operator to a `Style` = `(kind, inlineArgs, bodyIndent, childStyle,
statements)`; `LispFormatter` walks the tree tracking the current column.

- Kinds: `CALL` (arguments align under the first), `DATA` (elements align inside the delimiter),
  `BODY`/`CLAUSES` (`inlineArgs` on the operator's line, rest at `bodyIndent`), and `DO`, `LOOP`,
  `DEFMETHOD`, `CLAUSE` which need their own code (the last two read `inlineArgs` off the form).
- `childStyle` -- `(rec (list acc) body)` is indistinguishable from a call and `(t (foo) (bar))`
  from a call to `t`; only the ENCLOSING operator knows. This is how `labels` and `cond` say so.
- `statements` -- trailing arguments are an implicit `progn`; two or more get a line each however
  short. FALSE where the tail is alternatives or options (`if`, `cond` clauses, `defvar`'s value
  and docstring, `defstruct` slots, a `let` binding's init). It is checked inside `flat()`, not
  only at the top of a form: a form that must break must have NO one-line rendering, or an
  enclosing form that fits flattens it from above. `do`/`do*` is the one form that breaks on a
  body of ONE -- its three parts are told apart by layout alone.
- A `:key value` pair is one unit EVERYWHERE, not only in call position (a `defsystem` tail is a
  BODY of options). The pair takes its own line and the value stays beside the key; exception is
  depth -- `renderPairValue` sends a wrapping value below its key once the key's line has less
  room left than a fresh line would give.

### Width mechanics that are easy to get wrong
1. **Closing parens are part of the width.** `render` threads a `closers` count; omit it and every
   line runs one or two columns over the margin with no obvious cause.
2. **Every "move it elsewhere" rule is "would moving it help".** `movesToOwnLine` moves a
   distinguished argument down only when it does not fit where it is and does fit there, and
   refuses index 1.
3. **Align-under-first-argument COMPOUNDS.** `argumentColumn` gives the alignment up for
   `indent + 1` whenever the arguments do not fit at it, never charges depth for an alignment with
   nothing to align, and lets a single argument vote for `indent + 1` only when the ALIGNMENT is
   what puts it over. That question is `narrowest`, which returns TWO widths -- widest line and
   last line -- because trailing closing parens land on the last line only.

### A clause keeps a single body form beside a bare predicate
`Style.Kind.CLAUSE` (`cond`/`case`/`typecase`, and `do`'s end-test clause); how much stays on the
predicate's line is read off the FORM (`LispFormatter.clauseInlineArgs`). Conditions: the
predicate must be an ATOM; the body must be a SINGLE form (splitting a body of two across two
columns is the one thing a body may never do); and WIDTH. No bound on the predicate's width
works, so both renderings are measured and the lift taken only when the body needs no more lines
beside the predicate than below it and runs past the margin no more often (`shapeAt`: writes the
body at a column, looks, unwrites). `narrowest` is a LOWER bound and not honest enough here.

### Unknown operators are guessed from their name
`IndentRules.byNamingConvention`: `with-*`/`do-*` take one argument then a body, `without-*` a
body, `def*` a name and (if written with one) a lambda list then a body, anything else is a call.
Not cosmetic: a body-taking macro laid out as a CALL pushes its whole body thirty columns right.
The table also carries third-party body-takers seen in checked-in `.lisp` (uiop, alexandria,
rove's `testing`/`failing`/`setup`/`teardown`/`defhook`).

`def*` shape is read off the FORM by `isDefunShaped`: a name (atom), a second element that could
be a lambda list, then at least one more element. "Could be a lambda list" is a NEGATIVE test
(`isLambdaList`) -- a string, number, keyword, nested form, **`nil` or `t`** rules it out (a
lambda list may not bind a constant), which is what tells alexandria's
`(deftest xor.3 (xor nil nil nil) nil t)` from a definition. Ambiguity is decided in favour of
the lambda list; an operator that matters belongs in `RULES`.

## Deliberate divergences from trivial-formatter
Reference: https://github.com/hyotang666/trivial-formatter.
1. **No loading** -- the table is static and formatting purely syntactic, so a file formats
   without its dependencies. Cost: a macro's real shape is never consulted, hence the guess.
2. **Blank lines are preserved** (collapsed to one), wherever they are.
3. **Token case is preserved** (it downcases everything).

Trailing-comment alignment across consecutive lines is an ADDITION taken from gofmt; it runs as a
post-pass (`alignTrailingComments`) because the target column is unknown until the run is written.

## Known limit
Comments and string literals are never re-wrapped, so the margin is a target, not a guarantee;
`LispFormatterTest` pins the token stream and the fixpoint, NOT the margin. To tighten it, count
output lines over the margin that are neither a comment nor a trailing comment over
`LispFormatterTest.repositoryLispSources`, against the same count in the input; only the formatted
column is comparable across sessions, and only over the same tree. `rontolisp format --check` is
NOT wired into CI, so the tree drifts as files are added.
