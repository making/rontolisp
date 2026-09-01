# `read` on a stream consumes a whole line, so a second datum on that line is lost

Difficulty: Medium (the interpreter is easy; the JVM and WASM embedded readers
each own their own input path)

`read` reads a LINE and parses one datum out of it, discarding the rest --
"one datum per line", stated as the contract in `.kb/read-load-streams.md`.
CL's `read` consumes exactly the characters of one object and leaves the
stream positioned after it.

```lisp
(let ((s (make-string-input-stream "1 2 3")))
  (list (read s nil :eof) (read s nil :eof) (read s nil :eof)))
;; rontolisp: (1 :EOF :EOF)      SBCL: (1 2 3)

(with-input-from-string (s "(a) (b)")
  (list (read s nil :eof) (read s nil :eof)))
;; rontolisp: ((A) :EOF)         SBCL: ((A) (B))
```

The converse also holds and is the half that is harder to notice: a datum
SPANNING lines works only because the reader keeps reading lines until the
parens balance, which is why the shape survived this long.

Why it matters beyond conformance: a hand-written parser over a text file --
the shape chapters 3 and 15 of _Practical Common Lisp_ teach, and the shape a
`.lisp`-as-data config file has -- reads a fixed number of forms per file and
gets whatever the line breaks happen to allow. It is also the reason
`read-line` followed by `read` cannot be mixed on one stream.

The fix is a character-level reader over the stream rather than a line-level
one: the lexer already stops at the end of a datum, so what is missing is
pushing the unconsumed remainder back (a per-stream pushback buffer in the
stream table, shared by `read-char`/`peek-char`/`read-line`/`read`) instead of
dropping it. Interpreter: the `streams` table entry. JVM/WASM: the same buffer
beside `_read_line`'s state (`JvmReadRuntimeBuilder`, the WASM runtime reader);
WASM's string-input records already carry a `[cursor][end]` range, so the
cursor is the pushback.

Note `read-from-string` is a different path and is not affected -- it reads the
whole string; its missing INDEX second value is `.todo/214`.

Found by `.todo/620`. Related: `.todo/390` (`file-position` is nil on every
file stream -- the other half of "the stream has no position").
