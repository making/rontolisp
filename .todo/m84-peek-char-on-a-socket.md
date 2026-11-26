# m84. `peek-char` on a socket

Difficulty: Medium

Found by m63 (2026-11-26). `peek-char` is documented for every input stream
(`doc/en/reference/functions/peek-char.md`), but on a socket handle it is no stream on any
backend. Program: connect, peer sends `c3 41 0a`, `(peek-char nil s)`:

| backend | answer |
|---|---|
| interpreter | error `PEEK-CHAR expects an input stream` (`Environment`'s `peekChar` has no socket arm) |
| JVM | error `the value is not of the expected type` |
| component | trap (`unreachable`) |
| `--native` | `end of file` |

`read-char`, `unread-char` and `read-line` on the same socket agree on all four
(`(65533 65533 65 "")` for read, unread, read, read, read-line). The socket guide
(`doc/en/guides/tcp-sockets.md`) does not list `peek-char` among the socket operations.

## Plan

- Peek = `read-char` then park the character in the handle-side pushback `unread-char` uses,
  which `read-char`, `read-line` and `read-char-no-hang` already drain on every backend. Check
  `read-byte` and `read-sequence` after a peek: they do not consult that cell
  (`doc/en/reference/functions/unread-char.md`), so a peeked character's octets would be lost
  to them -- decide whether a byte read drains it back to octets or the doc states the limit.
- The compiled backends reach `peek-char` through `LispMacroExpander.expandPeekChar` onto
  `%peek-char`; on WASM the socket shape needs a `WasmSocketsRewrite` entry in all three
  dispatch providers (`StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes`).
- Pin with the `peek-type` forms (`nil`, `t`, a character) in `TcpOutputBufferFixture` or the
  cross-backend read-char program (`.kb/tcp-sockets.md`).
