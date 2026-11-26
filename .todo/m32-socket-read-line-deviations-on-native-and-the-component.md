# m32. A socket's `read-line` deviates from the oracle on `--native` and on the component

Difficulty: Medium

Found by l74 (2026-11-25), measured on the exec jar before l74's change and, for the invalid-byte
input, after it (identical: l74 keeps the wire bytes in the string as before). The oracle is SBCL
2.x (`sb-bsd-sockets`, `:external-format '(:utf-8 :replacement #�)`), which the interpreter and
the JVM agree with on every input below. The peer is a loopback listener that sends the bytes,
waits 0.3 s and closes; the program reads twice:

```lisp
(let ((s (rontolisp:tcp-connect "127.0.0.1" PORT)))
  (print (multiple-value-list (read-line s nil :eof)))   ; codes of the string, as (map 'list #'char-code l)
  (print (multiple-value-list (read-line s nil :eof))))
```

## 1. The missing-newline-p value is `NIL` at the end of input on `--native`

| peer sends (hex) | oracle = interpreter = JVM = component | `--native` |
|---|---|---|
| `78797a` ("xyz", no newline) | `((120 121 122) T) (:EOF T)` | `((120 121 122) NIL) (:EOF NIL)` |
| `78797a0a` ("xyz\n") | `((120 121 122) NIL) (:EOF T)` | `((120 121 122) NIL) (:EOF NIL)` |
| `c3a97a0a` | `((233 122) NIL) (:EOF T)` | `((233 122) NIL) (:EOF NIL)` |

The second value of a read-line that ends at the peer's close, and of the eof-value answer, is
`T` everywhere but `--native`. The source already writes it
(`%sock-read-line-f` ends `(%quiet-values ... (not newline))` / `(%quiet-values nil t)`, and
`%read-line-eof-future` re-issues `t` for the eof answer), so look at what a Preview 1 module does
with `%quiet-values` / `%quiet-multiple-value-list` across the plain defuns
(`.kb/multiple-values.md`), not at the line assembly.

## 2. An invalid UTF-8 byte in a line decodes differently, or traps, on both wasm backends

| peer sends (hex) | oracle = interpreter = JVM | `--native` | component |
|---|---|---|---|
| `ffc3a97a0a` | `((65533 233 122) NIL) (:EOF T)` | `((1849978) NIL) ...` | `((1849978) NIL) ...` |
| `ff7a0a` | `((65533 122) NIL) (:EOF T)` | trap: `out of bounds array access` | trap: `out of bounds array access` |

The line string holds the wire bytes as they are (`%str-from-octets`, formerly `%str-from-byte`
per byte), and the string layer's character walk (`WasmStringRuntimeBuilder.buildStrCharAtBody`,
the loose lead-byte-count decoder `buildIvUtf8StrBody` documents NOT being) reads `ff` as a lead
of a 4-byte sequence and runs past the string (the trap) or swallows the next bytes into one
character (1849978). `%sock-read-char-f` documents "an invalid lead byte stands alone as U+FFFD"
(`.kb/tcp-sockets.md`) and does decode so; the line path does not. A valid multi-byte sequence
split across chunks is fine and must stay so.

## Where

- `src/main/resources/am/ik/rontolisp/eval/sockets.lisp`: `%sock-read-line-f`,
  `%sock-read-char-f` (component).
- `src/main/resources/am/ik/rontolisp/eval/runner-sockets.lisp`: `%sock-read-line-f`,
  `%read-line-eof-future` (`--native`).
- `src/main/java/am/ik/rontolisp/codegen/wasm/WasmStrByteCompiler.java` (`%str-from-octets`,
  `%str-from-byte`), `WasmStringRuntimeBuilder.java` (`buildStrCharAtBody`, `buildIvUtf8StrBody`).
- Interpreter reference: `SocketSupport.readLine` (`new String(bytes, UTF_8)`).

## Plan

- Fix 1 first (it is a plain value), pin it in `TcpOutputBufferFixture` or `LineStartFixture` (add
  the no-newline and eof-value reads to an existing program, not a new test) on the four backends.
- For 2, decide between building the line through the lenient `%octets-to-string-packed` once the
  runs are joined (valid UTF-8 -- the common case -- is one `array.copy` either way; the
  malformed bytes become U+FFFD to match the oracle, which `%octets-to-string`'s own rule, "their
  own characters", does NOT: it answers Latin-1 characters, so check it against the oracle row
  above first) and a validating `%str-from-octets`. A line split mid-sequence across chunks needs
  the runs joined before decoding. Add `ffc3a97a0a` and `ff7a0a` to the same fixture.
