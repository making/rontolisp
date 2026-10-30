# h69. drakma on the compiled backends (first blocker: puri's `*read-suppress*`)

Difficulty: Medium

drakma loads and `http-request` answers (http, https, text and binary bodies) on the
interpreter since the flexi-streams external-format work (`.kb/gray-streams.md`). Compiling
the same program to a class fails first in puri (2026-10-31):

```
(ql:quickload "drakma")
(print (nth-value 1 (drakma:http-request "http://example.com/")))
```

`-o Dr.class`: `Error: while compiling defun PURI::SHARP-U: Cannot compile symbol reference:
*READ-SUPPRESS*` (puri `src.lisp:1343`, `(if *read-suppress* ...)` in its `#u` reader macro).
`*read-suppress*` is an interpreter variable (`.kb/read-load-streams.md`) with no compiled
binding. Give it one on every backend (a reader macro runs in a compiled program through the
readtable work, `.kb/readtables.md`), then measure the next blocker on JVM, WASM and the
component (usocket client, cl+ssl, chunga over the compiled Gray dispatch).
