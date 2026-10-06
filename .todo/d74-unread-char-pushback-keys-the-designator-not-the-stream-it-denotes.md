# d74. The `unread-char` pushback keys the designator, not the stream it denotes

Difficulty: Medium

The handle-side pushback (`.kb/gray-streams.md`, "Handle-side pushback") is keyed on the stream
argument AS GIVEN, with an omitted stream and nil folded onto `t`. A designator that DENOTES
another stream therefore parks the character where a read through the stream itself never looks.
Measured 2026-10-06:

| program | SBCL 2.2.9 | interpreter | JVM / P1 / component |
|---|---|---|---|
| `(with-input-from-string (s "abc") (let ((*standard-input* s)) (unread-char (read-char))) (print (read-char s)))` | `#\a` | `#\b` | `#\b` |
| `(defvar *x* nil)` then `(with-input-from-string (s "abc") (setq *x* s) (let ((y (make-synonym-stream '*x*))) (unread-char (read-char y) y)) (print (read-char s)))` | `#\a` | `#\a` | `#\b` |

The first is wrong everywhere: the omitted stream reads `*standard-input*` (the stream `s`) but
parks under `t`. The second is a cross-backend divergence: the interpreter's Gray wrap resolves the
synonym (`Environment.synonymTarget`) before the built-in sees it, the compile paths key on the
synonym value.

Resolve the key to the stream the designator denotes -- nil / omitted to the current
`*standard-input*`, a synonym to its target -- on all four backends. Watch the cost: naming
`*standard-input*` in `unread-char.lisp` switches on the standard-input redirect
(`StreamDesignators.resolveInput`) for every program that uses `unread-char`.
