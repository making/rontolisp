# `uiop/stream` part 1: file contents, slurping and safe IO

Difficulty: High

**`.todo/353` (the skeleton) has landed.** The 15 sub-packages are registered,
the target is the checked-in `uiop-exports.txt` (435 export rows / 429 distinct
symbols), and every export nothing implements yet already signals
`uiop:not-implemented-error` naming the operation -- so this item REPLACES stubs,
it does not add names. Read `.kb/uiop.md` first: a definition carries its HOME
sub-package's spelling, and a new one goes in that sub-package's `.lisp` resource.
Measured coverage here today (`UiopCoverageTest.printCoverage`, the authority for
every count below): **3 / 66 for `uiop/stream` as a whole (`read-file-string`, `default-temporary-directory`, `with-temporary-file`)**.

Depends on `.todo/353`, `.todo/354`, `.todo/357`. Part 2 is `.todo/360`;
`uiop/stream` is 66 externals and does not fit one batch.

Present already: `read-file-string`, `with-temporary-file`,
`default-temporary-directory` (the last two belong to part 2's subject). This
item is the **35** that answer "give me the contents of this file / stream":

```
CALL-WITH-INPUT-FILE  WITH-INPUT-FILE   CALL-WITH-OUTPUT-FILE WITH-OUTPUT-FILE
WITH-INPUT            WITH-OUTPUT       INPUT-STRING          OUTPUT-STRING
COPY-STREAM-TO-STREAM CONCATENATE-FILES COPY-FILE
SLURP-STREAM-STRING   SLURP-STREAM-LINES SLURP-STREAM-LINE
SLURP-STREAM-FORM     SLURP-STREAM-FORMS
READ-FILE-FORM        READ-FILE-FORMS   READ-FILE-LINE        READ-FILE-LINES
SAFE-READ-FILE-FORM   SAFE-READ-FILE-LINE
WITH-SAFE-IO-SYNTAX   CALL-WITH-SAFE-IO-SYNTAX SAFE-READ-FROM-STRING
EVAL-INPUT            EVAL-THUNK        STANDARD-EVAL-THUNK
FILE-STREAM-P         FILE-OR-SYNONYM-STREAM-P FINISH-OUTPUTS
PRINTLN               WRITELN           FORMAT!               SAFE-FORMAT!
```

## What decides the shape

- **The designator convention.** `with-input` / `with-output` /
  `input-string` / `output-string` accept a stream, `t`, `nil`, a string or a
  pathname and mean something different for each. Get this table right once, in
  one helper, and the twelve callers above are thin -- upstream is written that
  way for the same reason.
- **`with-safe-io-syntax` needs `*read-eval*` and a temporary package.** Check
  `.kb/read-load-streams.md` and `.kb/reader-features.md` for what the runtime
  reader honours on each backend before writing it; `safe-read-from-string` that
  silently ignores `:package` or `*read-eval*` is worse than one that signals.
  If some binding has no backend surface, that is a finding for this item.
- **`read-file-forms` / `slurp-stream-forms`** need a runtime `read` over a
  stream on all four backends. That exists (`.kb/read-load-streams.md`); confirm
  it does for the compile paths, not only the interpreter, before promising the
  four.
- **`copy-file` / `concatenate-files`** are the only write-side names in this
  half. They need `with-open-file` `:direction :output`, which
  `.kb/read-load-streams.md` says exists -- unlike `.todo/358`'s mkdir/unlink
  problem, so this half should not be blocked.
- **`println` / `writeln` / `format!` / `safe-format!`** are trivial, and
  `safe-format!` is the one that must never signal (it is what an error handler
  uses). Its fallback path is the reason it exists.

## Gate

`UiopCoverageTest` reports 35 more `uiop/stream` members bound. A `ci-spec.yaml`
case writes a file, reads it back with `read-file-lines` / `read-file-forms` /
`slurp-stream-string`, and round-trips through `with-output` to a string -- on
all four backends, since this family is the one real libraries reach for and the
component path uses a different I/O adapter.
