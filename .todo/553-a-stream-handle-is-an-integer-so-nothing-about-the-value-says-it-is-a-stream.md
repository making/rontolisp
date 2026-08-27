# 553. A stream handle is an integer, so nothing about the value says it is a stream

Difficulty: High

`.todo/552` closed its first half: a Gray stream IS a stream now, so `streamp` and
`(typep x 'stream)` answer `t` for a `rontolisp:fundamental-stream` subclass on all four
backends, and a library shaped like cl+ssl can be handed a wrapper it routes correctly.

The second half is untouched. An OPEN stream -- `open`, `make-string-input-stream`, a
socket, `*standard-output*`'s `t` designator -- is still a small integer index into a
per-backend table. Consequences that no widening of a predicate can reach:

- `streamp` cannot tell a stream from a file descriptor, or from any other integer:
  `(streamp 3)` is `t` and always will be while the representation is an integer. Upstream
  cl+ssl's `(etypecase socket (integer (ssl-set-fd handle socket)) (stream ...))` therefore
  still picks the descriptor arm for a raw handle (`.kb/cffi.md`).
- `file-stream` / `string-stream` cannot be told apart on the interpreter or the JVM
  (`LispMacroExpander.makeTypeTest`'s `FILE-STREAM` arm is `integerp`); only the WASM
  backends could, by the handle's sign.
- A stream cannot carry per-stream state a caller can read back -- `file-position` is a
  table lookup rather than a property of the value (`.todo/390`), and `print-object` on
  one prints an integer (`.todo/434`).

This is the same shape `.todo/156` asks about symbols: whether a first-class type should
stop being a primitive scalar. Answering it means choosing a self-describing value
(instance-over-a-fixed-layout, like `%PATHNAME` / `%SYNONYM-STREAM`, is the precedent
that already works on all four backends) and paying for it at every stream seam --
the reader/writer built-ins, the WASI and WASM-component I/O adapters, the socket
layer, the emitted reader, and every library shim that stores a handle.

## What a solution has to keep

- All four backends identical, pinned in `ci-spec.yaml` (`.kb/read-load-streams.md`).
- The handle table's identity semantics: `eq` on two reads of the same stream, `close`
  observable through `open-stream-p`.
- The `t` designator (`*standard-output*`) and the synonym-stream value, which already
  are not handles.
- No cost for a program that never names a stream: the instance gate must stay off.
