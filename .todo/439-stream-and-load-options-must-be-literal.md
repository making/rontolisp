# 439. `with-open-file` / `open` / `load` reject computed keyword arguments

Difficulty: Medium

Child of `.todo/436` (read it first). Wave 1. Probably the highest value per
line of work on the list: it is what stops a portable file-handling wrapper from
existing at all.

## The defect

`with-open-file` (and `open`) accept `:element-type` / `:external-format` /
`:if-exists` / `:if-does-not-exist` only as LITERALS.

```lisp
(defun rd (path et)
  (with-open-file (s path :direction :input :element-type et) (read-line s)))
;; => error: WITH-OPEN-FILE :element-type must be the literal 'character
;;           or '(unsigned-byte 8)
```

That shape -- a function taking the options as arguments and passing them down --
is how every portable library opens a file; upstream uiop's
`call-with-input-file` / `call-with-output-file` are exactly it.

And `cl:load` takes no keyword arguments at all:

```lisp
(load "foo.lisp" :verbose nil)   ; => LOAD expects 1 argument, got 3
```

At minimum `:verbose` / `:print` / `:if-does-not-exist` / `:external-format`
must be accepted (semantically ignorable where there is nothing to do).

## Watch

- **Literal arguments must still produce byte-identical code.** Folding a
  literal at compile time is fine and wanted; the existing output and
  `size-report/` numbers depend on it.
- `load` is spliced at compile time by `cli/LoadInliner` (`.kb/load-inliner.md`).
  Decide what a keyworded top-level `load` does there -- splice as today, or fall
  through to the runtime path -- and write the reason into the `.kb` file.
- Decide and document what an `:element-type` other than `character` /
  `(unsigned-byte 8)` does.

Read `.kb/gray-streams.md`, `.kb/error-handling.md`, `.kb/load-inliner.md`, and
the `doc/en` + `doc/ja` pages for the operators.

## Acceptance

Both snippets run on all four backends; literal-argument output unchanged; a
ci-spec case (`computed-stream-options-439`).
