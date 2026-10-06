# d59. `with-input-from-string :index` drains the stream and misses a pushed-back character

Difficulty: Medium

The `:index` place is stored as the bound end minus the characters a `read-char` drain still
finds (`LispMacroExpander.withInputFromStringIndexed`). The drain is synthesized inside the
expression compilers, after `UnreadCharLibrary` rewrote the program's `read-char` call sites
onto the pushback cell, so it reads past a character `unread-char` parked and leaves the cell
set. Measured 2026-10-06:

```lisp
(let ((i 0))
  (with-input-from-string (s "123  " :index i)
    (read-char s) (read-char s) (read-char s)
    (unread-char (read-char s) s))
  i)
```

SBCL and the interpreter answer `3`; JVM, P1 and the component `4`. With
`(unread-char (read-char s) s)` over `"abc"` the compiled backends signal a `simple-error`
(SBCL: `0`), and a later `unread-char` on another stream can then fail with "UNREAD-CHAR
without an intervening READ-CHAR".

A string input stream's `file-position` answers the character position on all four backends
(a pushed-back character not counted, as SBCL) -- except in a `--no-wasi` module, where
`file-position` is the constant nil for every stream although a string stream needs no WASI.
`%read-from-string-full` asks `file-position` and drains only when it answers nil, for that
reason. Two steps, on all four backends:

- Let a `--no-wasi` module answer `file-position` for a string stream (the fd arm keeps nil),
  then drop both drains: the `:index` store becomes `(+ start (file-position s))`, and the
  prelude defun's fallback goes.
- Until then, make the `:index` drain reach the pushback cell (a call site `UnreadCharLibrary`
  sees, or the pushback-aware reader when the program uses `unread-char`).

Note the `.kb/read-load-streams.md` paragraph on `with-input-from-string` and the javadoc of
`LispMacroExpander.withInputFromStringIndexed` while there.
