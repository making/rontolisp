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

## Consumer: rove (2026-08-15, `.todo/372` spike) -- the contract widens

Rove's spec reporter writes through its own Gray stream (`rove/misc/stream`):

```lisp
(defclass indent-stream (trivial-gray-stream-mixin fundamental-character-output-stream)
  ((stream ...) (level ...) (line-column ...) (fresh-line-p ...)))
(defmethod stream-write-char ((stream indent-stream) char) ...)   ; the ONLY writer it defines
(defmethod stream-line-column ((stream indent-stream)) ...)
(defmethod stream-start-line-p ((stream indent-stream)) ...)
(defmethod stream-finish-output / stream-force-output / stream-clear-output ((stream indent-stream)) ...)
```

driven by `fresh-line`, `princ`, `format`, `write-char`, `write-string`. Beyond
the six operators above, the spike found:

1. `write-char` on a Gray instance routes to `stream-write-string`
   (`%gray-write-char-dispatch`: "write-char lowers to write-string everywhere,
   so the dispatch does too") and the base classes have NO default
   `stream-write-string`, so a class defining only `stream-write-char` -- the
   Gray protocol's one required method -- fails: "No applicable method:
   STREAM-WRITE-STRING on INDENT-STREAM". `write-char` must reach
   `stream-write-char`, and `fundamental-character-output-stream` needs the
   Gray default `stream-write-string` looping `stream-write-char` (both
   rontolisp's protocol and the trivial-gray-streams delegations).
2. New generics with the Gray defaults, in rontolisp's protocol AND as
   `trivial-gray-streams:` spellings (rove's package `(:use
   #:trivial-gray-streams)`, so an unknown name silently interns as a dead
   local generic): `stream-line-column` (default nil), `stream-start-line-p`
   (default: column known and 0), `stream-terpri` (write-char newline),
   `stream-fresh-line` (`(unless (stream-start-line-p s) (stream-terpri s) t)` --
   this is what makes rove's report layout come out right; a stream with no
   column falls back to the plain newline the item above describes),
   `stream-finish-output`/`stream-force-output`/`stream-clear-output` (nil),
   `stream-advance-to-column`. `clear-output` itself does not exist as a
   built-in ("The function CLEAR-OUTPUT is undefined") -- add it with the other
   two.
3. On the COMPILE paths `princ`/`prin1`/`print`/`write`/`terpri`/`fresh-line`/
   `write-line` on a Gray instance are not rewritten (`GrayStreamsLibrary`
   rewrites write-string/write-char/format/...; the kb's broadcast section lists
   `princ` as working, the interpreter's `emitTo` does dispatch it), so the JVM
   and WASM write the text to standard output PAST the instance: rove's report
   comes out unindented with the newlines lost, while the interpreter's is
   right. The rewrite list must cover the whole print family.

Acceptance addition: rove's indent-stream shape (a `stream-write-char`-only
class with `stream-line-column`/`stream-start-line-p`) receiving `princ`,
`format`, `fresh-line`, `write-string`, `write-char`, `finish-output` prints the
same bytes on all four backends (`RoveE2eTest`, `.todo/372`, and a direct
per-backend pin).
