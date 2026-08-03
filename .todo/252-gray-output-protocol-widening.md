# 252. The Gray output protocol stops at write-char/write-string

Difficulty: 中 (mechanical per operator -- the interpreter wrap, the
`GrayStreamsLibrary` rewrite entry and the dispatch defun already have five
worked examples -- but the Preview 1 `listen` precedent says the SPLICE must
stay on-use, and each addition needs its four-backend pin.)

Surfaced by `.todo/249`: `make-broadcast-stream` with components is now a Gray
output stream (`.kb/gray-streams.md`), so a broadcast stream inherits exactly
the protocol's coverage -- and the protocol covers only the two write generics.

```lisp
(let ((b (make-broadcast-stream *standard-output* f)))
  (format b "ok~%")   ; works  (format / princ / write-string / write-char)
  (terpri b))         ; => "not an output stream: #<%BROADCAST-STREAM ...>"
```

Not broadcast-specific: ANY user Gray stream has the same hole, and it has had
it since the protocol landed. What does not dispatch today, verified on the
interpreter with a `fundamental-character-output-stream` subclass:

| operator | today |
| --- | --- |
| `terpri` | `not an output stream` |
| `fresh-line` | `not an output stream` |
| `write-line` | `WRITE-LINE expects an output stream` |
| `force-output` / `finish-output` | `FORCE-OUTPUT expects an output stream` |
| `print` | `not an output stream` |
| `close` | `CLOSE expects a stream` |

CL's Gray protocol names `stream-terpri`, `stream-fresh-line`,
`stream-force-output`, `stream-finish-output`, `stream-clear-output`,
`stream-line-column` and `close` generics for exactly this. The cheap and
honest subset here:

- `terpri` / `fresh-line` / `write-line` need no new generic -- they are
  `stream-write-char` / `stream-write-string` compositions, so the dispatch
  helpers can compose the two the protocol already has. (`fresh-line` without a
  column is a plain newline; a stream with no column cannot do better, see
  `.kb/pretty-printer.md`.)
- `force-output` / `finish-output` / `close` DO want their own generics with
  default methods (a no-op and "return t"), since a user stream may need to
  flush or release something.
- `print` falls out of `write-line`/`terpri` + the existing printer.

Do it in the shape `.kb/gray-streams.md` documents: one interpreter wrap
(`applyGrayDispatch`), one `GrayStreamsLibrary` call-site rewrite entry, one
`%gray-*-dispatch` defun, and keep the splice ON-USE (`SPLICE_ON_USE`) -- an
unconditional splice is what broke every Gray program on Preview 1 when
`%gray-listen-dispatch` named the `listen` built-in that backend rejects at
compile time.

## Acceptance

- The six operators above dispatch to a Gray instance on all four backends,
  with the compile-path rewrite and the on-use splice.
- A broadcast stream with components accepts them (add the case to the
  `make-broadcast-stream` doc page, which currently states the limitation).
- `.kb/gray-streams.md`: the "Limits" paragraph and the broadcast section's
  re-evaluation trigger both come out.
