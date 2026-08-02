# Gray binary/input stream classes, input generics, stream-file-position

Difficulty: 中〜高 (protocol widening across the interpreter dispatch and the
compile-path rewrite; all four backends)

Split out of `.todo/231` (the lack ecosystem survey). The LAST substrate
blocker before re-probing fast-io / circular-streams loading — the other two
blockers (multiple inheritance + setf methods + setf function-name places)
shipped as todo-232 (2026-08-02, `.kb/clos.md` / `.kb/symbol-runtime-api.md`).

## Current state (read `.kb/gray-streams.md` FIRST)

rontolisp owns the Gray protocol; `gray.lisp` today has ONLY the output side:
two classes (`rontolisp:fundamental-character-output-stream` / `-input-stream`
— the input class EXISTS but no input generic does) and two generics
(`stream-write-char`, `stream-write-string`). Dispatch: the interpreter wraps
the `write-string` builtin ("stream argument is an instance" test); the
compile path is `GrayStreamsLibrary.process` — a call-site rewrite of
`write-string`/`write-char`/`format` onto `%gray-write-string-dispatch`
defuns. The trivial-gray-streams shim subclasses and delegates.

## Gaps, in dependency order

1. **Binary stream base classes** in gray.lisp:
   `fundamental-binary-input-stream`, `fundamental-binary-output-stream`,
   `fundamental-input-stream`, `fundamental-output-stream`,
   `fundamental-stream` (the ancestry fast-io / circular-streams name;
   check the exact set the two libraries reference before adding more).
   With todo-232's multiple inheritance these can form the CL-shaped
   hierarchy instead of a flat list. `trivial-gray-stream-mixin` lives in
   the SHIM (trivial-gray-streams.lisp), not in gray.lisp.
2. **Input generics + read-side dispatch**: `stream-read-byte`,
   `stream-read-char`, `stream-unread-char`, `stream-read-line`,
   `stream-listen`, `stream-write-byte`, `stream-read-sequence` /
   `stream-write-sequence` (trivial-gray-streams spells the sequence pair
   itself — check its shim delegation). Needs the read-side twin of the
   write dispatch: interpreter wraps `read-byte`/`read-char`/`read-line`
   builtins on an instance stream; compile path extends
   `GrayStreamsLibrary.process` with `%gray-read-*-dispatch` rewrites.
   Beware the format-rewrite lessons in `.kb/gray-streams.md` (walker
   position-blindness; the run-time nil-destination test) — the read family
   has the same optional-stream-designator shape
   (`.kb/standard-output-redirect.md`).
3. **`stream-file-position` protocol**: the generic + a
   `(setf stream-file-position)` writer generic (todo-232's
   `(defmethod (setf name))` makes the user side definable). CL's
   `file-position` builtin is currently a hard-nil lite stub
   (`LispNames.FILE_POSITION` javadoc) — route an INSTANCE argument to the
   generic, keep the nil answer for handles. trivial-gray-streams defines
   its own `trivial-gray-streams:stream-file-position` — widen the shim to
   delegate both directions.

## Probe targets (re-probe = the acceptance test)

- circular-streams: `(defclass circular-input-stream
  (trivial-gray-stream-mixin fundamental-binary-input-stream) ...)`,
  `stream-read-byte`/`stream-read-char` methods,
  `(defmethod (setf stream-file-position) ...)`,
  `(setf (fdefinition 'make-circular-stream) #'make-circular-input-stream)`.
- fast-io: `fast-output-stream (fast-io-stream fundamental-output-stream)`,
  `fast-input-stream` likewise, `(setf stream-file-position)` methods, the
  `write8-le` symbol-function aliases (todo-232 covers those).
- Read both sources from the ql:quickload cache, never from GitHub master
  (memory note: fetch-lisp-sources-via-quicklisp).

## Constraints

- All four backends; gray.lisp is backend-free expansion output, so most of
  the work is Lisp source + the two dispatch seams (interpreter builtin
  wrap, `GrayStreamsLibrary.process`).
- Keep non-Gray programs byte-identical: process() only fires on a protocol
  name in the program — new rewrites must stay behind the same trigger.
- `write-string` bounded form and the input-side echo of that limitation:
  document what stays out.
- ci-spec case for the binary round trip (read-byte/write-byte through a
  user Gray stream) + the file-position protocol.
