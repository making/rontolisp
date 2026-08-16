# 400. No built-in dispatches to the Gray INPUT protocol

Difficulty: Medium

Found by the dexador spike (`.todo/396`). `.kb/gray-streams.md` says it
plainly: `stream-read-char`, `stream-unread-char` are "protocol-only, no
built-in dispatches". The generics exist, a class may define them, and nothing
ever calls them -- so a CLOS instance is a usable OUTPUT stream (todo-252
widened that side) and is NOT a usable input stream.

`(dex:get url :want-stream t)` answers dexador's `decoding-stream`, a
`trivial-gray-streams:fundamental-character-input-stream`. Reading one line
from it fails, differently on each backend:

| backend | `(read-line s)` |
| --- | --- |
| interpreter | `READ-LINE expects an input stream` |
| JVM | `the value is not of the expected type` |
| WASM `--component` | answers `NIL` -- silently, no error |

The third row is the worst of the three and is reason enough on its own: a
program that reads a streamed body gets an empty answer and no signal.

## The work

- Make the input built-ins dispatch to the protocol when the argument is a
  CLOS instance of `rontolisp:fundamental-input-stream`, exactly as the
  output built-ins do for the output side: `read-char`, `read-char-no-hang`,
  `peek-char`, `unread-char`, `read-line`, `read-byte`, `read-sequence`,
  `listen`, `close`, `open-stream-p`, `stream-element-type`.
- Mirror todo-252's "exactly one required method" widening on the input side:
  `stream-read-line` has an obvious default over `stream-read-char`, as does
  `stream-read-sequence`; `stream-read-char` is the one a class must supply.
  Write down which method is required, since the trade-off is the same one
  todo-252 settled for output.
- The three backends must AGREE, including the failure when a class supplies
  no reader at all -- the `--component` silent `NIL` above is the shape to kill.
- `peek-char`/`unread-char` need the one-character pushback the protocol's
  `stream-unread-char` implies; decide whether the built-in keeps that state or
  the class does (real Gray says the class does).
- Pin with a Gray input class defined in Lisp, driven through every built-in
  above on all four backends, plus a `ci-spec.yaml` case; update
  `.kb/gray-streams.md` (its "no built-in dispatches" sentence is the thing
  being retired).
