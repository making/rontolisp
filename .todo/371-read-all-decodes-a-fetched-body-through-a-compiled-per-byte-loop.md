# `read-all` decodes a fetched body through a compiled per-byte loop

Difficulty: Medium

Since todo-370 every HTTP body stream answers OCTET chunks and
`rontolisp:read-all` decodes the joined body once, through the prelude's
`rontolisp::%octets-to-string` -- the LENIENT UTF-8 decoder (a byte that leads
no valid sequence is its own character), one Lisp definition compiled on the
JVM and both WASM backends, mirrored natively in Java on the interpreter
(`Environment.decodeUtf8Leniently`, `.kb/async-await.md`). Correct on all four
backends and pinned; the cost is the compiled loop where the old paths were a
memcpy. Measured 2026-08-15, a 500 KB ASCII body:

| backend | `%octets-to-string` | before todo-370 |
| --- | ---: | --- |
| interpreter | 32 ms | a `CharsetDecoder` per chunk (comparable) |
| JVM | 45 ms warm, 246 ms cold | `BodyHandlers.ofString()` (a JDK decode) |
| wasm (`--component`, `--no-wasi`) | 107 ms | the byte-string lift, a raw copy |

Roughly 90 ns/byte warm on the JVM and 200 ns/byte on wasm: `aref` through the
packed dispatch, `code-char` boxing and `write-char` into a string output stream
per byte. Fine for an API reply of a few KB, a visible stall for a megabyte.

## The shape of the fix

A STRICT native fast path with the lenient loop as the fallback, so the answers
stay identical by construction:

```lisp
(defun rontolisp::%octets-to-string (v)
  (or (rontolisp::%octets-to-string-strict v)   ; the string, or nil if V is not valid UTF-8
      <the lenient loop as it is>))
```

- interpreter: `CharsetDecoder` with `CodingErrorAction.REPORT`, nil on a
  `CharacterCodingException` (the lenient Java mirror stays for the fallback);
- JVM: the same decoder in emitted bytecode (`JvmAsyncRuntimeBuilder` beside
  `_iv_of_bytes`: unpack the `long[]` to `byte[]`, `newDecoder().decode`, catch);
- wasm-GC: a runtime function that VALIDATES the `TYPE_I8ARR` as UTF-8 (the
  four lead-byte ranges plus continuation checks -- `_str_char_at`'s decoder is
  NOT that validator, its ranges are looser, which is why a raw copy alone was
  rejected in todo-370) and, valid, builds the string by one `array.copy` into
  `$str_bytes` between the two quote bytes; invalid answers nil.

Malformed input -- rare -- takes the loop; everything else takes the platform
decoder. On wasm this restores the memcpy the component's fetch had.

## Gate

- The three natives agree with the lenient loop on valid UTF-8 (pin the same
  strings the ci-spec `read-all-decodes-an-octet-chunk-stream` case decodes,
  plus a 4-byte code point) and answer nil on the case's `#xFF` and on a
  truncated sequence.
- The 500 KB decode drops to the memcpy tier on wasm (`< 5 ms`) and to the JDK
  decode on the JVM; the ci-spec case and the todo-370 relay tests stay green.
