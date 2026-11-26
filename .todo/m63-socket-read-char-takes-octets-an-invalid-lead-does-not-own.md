# m63. A socket's `read-char` takes octets an invalid lead does not own

Difficulty: Medium

Found by m32 (2026-11-26). `read-char` on a socket assembles a character from the count its
first octet's high bits give, whatever the octets after it are, and decodes that. The
documented rule (`doc/en/reference/functions/read-char.md`: a malformed sequence is one
U+FFFD and "the wrong byte then starts the next character", `41 E9 42` reads `A`, U+FFFD,
`B`) holds for a file stream, not for a socket, on any backend. Peer sends the octets and
closes; the program reads three characters with `(read-char s nil :eof)`, codes printed:

| peer sends (hex) | SBCL 2.x `:replacement` | interpreter = JVM | `--native` = component |
|---|---|---|---|
| `ff7a0a` | `65533 122 10` | `65533 :EOF :EOF` | `2073250 :EOF :EOF` |
| `c3410a` | `65533 65 10` | `65533 10 :EOF` | `193 10 :EOF` |
| `41e9420a` | `65 65533 66` | `65 65533 :EOF` | `65 37002 :EOF` |

The interpreter and the JVM swallow the octets after the lead (`SocketSupport.readChar`, the
JVM's socket `read-char` arm); the WASM targets also hand the octets to the string layer's
loose character walk (`%sock-read-char-f`, `(char acc 0)`), which answers a code point the
octets do not spell. `read-line` is right on all four (m32: the line is decoded once whole,
`%str-decode-raw`).

## Plan

- One rule on every backend, the line's: the maximal subpart (the prelude's
  `%octets-to-string-replacing`): an octet that cannot continue the sequence is left unread
  for the next read. That needs a peek of one octet past what is taken: the interpreter's and
  the JVM's socket input (check what `in(socket)` supports), the WASM chunk (the octet at the
  cursor, after a fill when the chunk is spent).
- Pin it in `TcpOutputBufferFixture` (or the cross-backend read-char program the
  `.kb/tcp-sockets.md` "Cross-backend pin" names) with the three rows above, on the four
  backends. `peek-char` and `unread-char` on a socket ride the same read; check them.
