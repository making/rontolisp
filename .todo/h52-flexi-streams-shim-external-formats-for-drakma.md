# h52. flexi-streams shim: external-format objects and the flexi-stream accessors (blocks drakma)

Difficulty: Medium

Found 2026-10-30 probing `drakma`: `The function DRAKMA::MAKE-EXTERNAL-FORMAT is undefined`
(`specials.lisp:41`, `(define-constant +latin-1+ (make-external-format :latin-1 :eol-style :lf))`).
drakma `:use`s `flexi-streams`. Calls it makes into that package (drakma + chunga sources):

| call | sites | in the shim |
|---|---|---|
| `flexi-stream-external-format` (+ `setf`) | 8 | no |
| `flexi-stream-stream` | 7 | no |
| `flexi-stream-element-type` (+ `setf`) | 7 | no |
| `make-flexi-stream` | 3 | yes |
| `make-external-format` | 2 | no |
| `external-format-in` / `external-format-out` | 1 each | no |
| `with-input-from-sequence` / `with-output-to-sequence` | 1 each | no |
| `string-to-octets` / `octets-to-string` | 1 each | yes |

drakma switches one socket stream between octets (bodies) and characters (headers) by
`setf`ing the flexi-stream's element type and external format. Implement
`external-format` objects (name + `:eol-style`, with the shim's UTF-8/Latin-1/ASCII codecs,
`.kb/asdf.md` babel), those accessors over the existing `flexi-stream` wrapper, and the two
sequence macros. Or follow `.todo/147`'s plan and load the real flexi-streams if the stream
model allows it now; measure that first. drakma's later blockers (cl+ssl, chunga, usocket
client) are unmeasured.
