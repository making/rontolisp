# n08. `peek-char` on standard input in an async component

Difficulty: Medium

Found by m84 (2026-11-27). Where stdin.lisp is spliced (an async component reading stdin,
or any component program with sockets), `read-char` / `read-line` on standard input read
stdin.lisp's chunk, but `peek-char` reaches the native `_peek_char`, which reads the
adapter's own stdin. Stdin `abc\n`, `(list (peek-char) (read-char) (peek-char nil nil nil :eof)
(read-line))`:

| backend | answer |
|---|---|
| interpreter, Preview 1 | `(#\a #\a #\b "bc")` |
| component, inside an `async-defun` (stdin-dispatch.lisp) | `(#\a #\b #\a "c")` |
| component, a socket program at top level (sockets.lisp) | `(#\a #\b #\a "c")` |

## Plan

- The socket rewrite already sends every `peek-char` to `%io-peek-char` /
  `%peek-char-future`; their non-socket arm is `%peek-char-raw`. Give the stdin designator
  (nil / t after `%stream-target`) an arm that looks at stdin.lisp's chunk without moving
  `*stdin-cursor*`, refilling behind the unread octets as sockets.lisp's `%sock-keep` does
  (`.kb/tcp-sockets.md`, "peek-char on a socket"), in sockets.lisp and stdin-dispatch.lisp
  alike (`StdinLibraryTest#theThreeDispatchSplicesDefineTheSameNamesAndShapes`).
- Keep a program that never peeks byte-identical (the rewrite drops the unreached peek
  defuns, `WasmSocketsRewrite.PEEK_DEFUNS`).
- Pin beside `componentAsyncStdinBareReadCharSignalsTheEndOfFileClass`.
