# 813: the four questions that separate bytes from characters

```bash
JAR=target/rontolisp-0.1.0-SNAPSHOT-exec.jar ./bytes.sh
```

`bytes-interp.lisp` asks the interpreter; `bytes.lisp` exports the same four questions so
a host can drive them with a RUNTIME `:string` (a literal behaves the same, and `--no-gc`
has no `format`, so the answers have to come back as `:s32` rather than as printed text).
`bytes.mjs` writes the input through `__ronto_alloc` and prints one row per input, in the
interpreter's own list spelling, so the three outputs diff directly.

Expected, for `"abc"` / `"日本語"` / `"aé日"` as
`(length (char-code (char s 0)) (char-code (char s 1)) (length (subseq s 1)))`:

| | interpreter, JVM, wasm GC | `--no-gc` |
| --- | --- | --- |
| `"abc"` | `(3 97 98 2)` | `(3 97 98 2)` |
| `"日本語"` | `(3 26085 26412 2)` | `(3 26085 26412 2)` |
| `"aé日"` | `(3 97 233 2)` | `(3 97 233 2)` |

All three agree since 2026-09-14, when `.todo/813` paid for code points
(`__strlen_cp` / `__byte_offset` / `__char_at`, each gated on its operator).
Before that `--no-gc` answered the middle row as `(9 230 151 8)` and the last as
`(6 97 195 5)`: 230 and 151 are the first two bytes of 日's UTF-8 encoding --
`(char s 1)` did not answer a character at all, it answered a continuation byte.
ASCII agreed everywhere even then, which is why this went unnoticed.
