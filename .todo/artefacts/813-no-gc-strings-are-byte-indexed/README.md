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
| `"日本語"` | `(3 26085 26412 2)` | `(9 230 151 8)` |
| `"aé日"` | `(3 97 233 2)` | `(6 97 195 5)` |

230 and 151 are the first two bytes of 日's UTF-8 encoding: `(char s 1)` does not answer a
character at all, it answers a continuation byte. ASCII agrees everywhere, which is why
this went unnoticed.
