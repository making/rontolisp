# e02. Component async stdin: an eof-argument read skips the buffered input

Difficulty: Low

In an async `--component` program (stdin.lisp spliced), a read with eof arguments after a
plain one loses what stdin.lisp already buffered:

```lisp
(rontolisp:async-defun main ()
  (print (read-line))
  (print (read-line *standard-input* nil :eof)))
(rontolisp:await (main))
```

Over standard input `s1\ns2` (no trailing newline): interpreter / JVM / Preview 1 print
`"s1"` `"s2"`, the component `"s1"` `:EOF`.

`(read-line)` reaches `%stdin-read-line-f` (the wit stream, buffered in
`*stdin-buf*`); the 3-argument form is rewritten to `%read-line-eof-future`, whose
non-socket arm is `(%read-line-raw s eof-error-p eof-value)` -- the native fd 0 read,
which never sees the buffer. `%read-char-eof-future` has the same shape.

## Plan

- Failing four-backend test first (async program over a stdin file).
- Route the eof-argument futures' stdin arm through `%stdin-read-line-or-raw-f` /
  `%stdin-read-char-or-raw-f` and apply the eof arguments on top, keeping the
  missing-newline-p second value (`.kb/multiple-values.md`).
