# The composite stream classes as CL type names, and the zero-component broadcast stream

Difficulty: Medium (type names on four backends plus a handful of special cases;
the composite streams are prelude Gray classes today)

Split out of `.todo/919` (2026-09-22), whose table listed it as a cluster but whose
plan did not cover it.

## Measured (2026-09-22, interpreter, suite `ca06bd9`, ANSI `streams` after `.todo/919`)

| test | reason |
|---|---|
| `MAKE-BROADCAST-STREAM.1 .2 .3`, `MAKE-TWO-WAY-STREAM.1` | `(typep s 'broadcast-stream)` / `'two-way-stream` is false: the composite classes are not CL type names |
| `MAKE-BROADCAST-STREAM.5 .7 .8` | a ZERO-component broadcast stream: `file-length` wants 0, `file-string-length` 1, `stream-external-format` `:default` -- ours is the string-output sink, so NIL / 28 / `:UTF-8` (`.6`, `file-position` 0, passes since `.todo/929` gave a string output stream its position) |
| `BROADCAST-STREAM-STREAMS.1 .3 .4` | the zero-component sink is a `%STREAM`, so `%broadcast-stream-components` has no method; `.3`/`.4` then read NIL as an integer |

## What it needs

- `broadcast-stream`, `two-way-stream`, `echo-stream`, `concatenated-stream` in
  `PackageRegistry.CL_TYPES` AND `LispMacroExpander.makeTypeTest` (a name in the
  first without the second is a hard `typecase` error, `.kb/read-load-streams.md`,
  "Stream TYPE names are all EXACT").
- Decide whether `(make-broadcast-stream)` stays the string-output sink (it is
  what keeps a discarding sink from dragging the Gray protocol in -- measured in
  `LispPreludeLibrary.referencedBySurfaceForm`'s comment) or becomes a real
  zero-component broadcast stream; the ANSI special cases need the latter's
  answers, which can also be given by the sink if it is told apart.
- The other composite-stream failures in the same chapter (`MAKE-ECHO-STREAM.*`,
  `MAKE-CONCATENATED-STREAM.*`, `MAKE-TWO-WAY-STREAM.2 .5 .6 .10`) are `listen` /
  `unread-char` / `open-stream-p` semantics, not type names -- measure before bundling
  them in. The direction predicates are real since `.todo/929` (`MAKE-SYNONYM-STREAM.1 .3`
  pass).
