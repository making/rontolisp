# 690. A character index into a string that holds a surrogate pair walks from the start

Difficulty: Medium

Found 2026-09-03 by `.todo/677`: `(rontolisp:json-parse text)` over Qwen3.5-0.8B's
`tokenizer.json` -- 13 MB, 248k vocabulary entries -- did not finish in 10 minutes on
the JVM class output (`examples/llama2/llama2.lisp`, `load-hf-tokenizer`), nor on the
interpreter. `jcmd Thread.print` sampled at 60 s and 180 s showed the same frame both
times:

```
java.lang.StringUTF16.codePointCount
Llama._cpoff
Llama._charRef
Llama.RONTOLISP::%JSON-SKIP-WS
Llama.RONTOLISP::%JSON-OBJECT
```

The file holds NO surrogate pair (checked: 0 code points above U+FFFF, 745k above
U+00FF, so the `String` is UTF16-backed). `JvmStringIndexRuntimeBuilder`'s `_cpoff`
indexes such a string directly once it is PROVEN surrogate-free, and the proof --
`codePointCount(1, len - 1) == len - 2`, O(n) on a UTF16 string -- is remembered in a
two-entry memory of proven strings. A JSON parse interleaves every access to the big
string with fresh small strings (each key it cuts out is proven and remembered too), so
the big string is evicted every few characters and re-proven at O(n) on the next
access: a left-to-right parse of N characters is O(N^2) -- the memory that was meant to
make the scan linear thrashes on exactly the workload it was built for. At 13 MB that is
hours. The interpreter's `Environment` does the same arithmetic (`codePointCount` /
`offsetByCodePoints` per access) and is quadratic the same way.

**Measured, not reasoned**: the vocabulary is 248070 entries (248044 in `vocab` plus the
added tokens) and 247587 merges, 10.77M characters of JSON. `rontolisp:json-parse` over
that text was still inside the `vocab` object after 10 minutes on the JVM class output
(two 10-minute runs, the second sampled at 60 s and 180 s -- the frames above), and a
third run on the interpreter did not print its first timing line in 7 minutes. The
byte-level reader below parses the same file in **2.4-2.7 s** (the "tokenizer + kv
cache" figure of the example's load line, which also builds the tokenizer), so the
factor is at least 250x on this vocabulary -- the cost is real at a real checkpoint's
size, not a bound. The related axis -- the three hand-written UTF-8 decoders between
octets and strings -- is `.todo/691`; this item is the character-INDEX side of the same
octets/string boundary.

`examples/llama2/llama2.lisp` works around it with a byte-level JSON reader of its own
(`json-parse-bytes`: the tokenizer.json is read as an `(unsigned-byte 8)` vector and
scanned with typed loops, each token string decoded on its own), which is what lets the
0.8B model start in seconds. That reader is the measurement; the fix belongs here.

## Do

1. Give a string with a surrogate pair an O(1) (or O(log n)) character index: a lazily
   built breakpoint table (code-unit offset every K characters) attached to the string --
   on the JVM a side table keyed by identity (a `WeakHashMap`, or a field on a string
   wrapper if the framed-string representation grows one), on the interpreter a field of
   `LispString`. `_scount` / `length` read the same table.
2. Pin it: a test that `char`-walks a 1M-character string holding one emoji at the
   front and asserts the time is linear (or counts `codePointCount` calls), on the
   interpreter and the JVM; and `json-parse` of a 10 MB string with a surrogate pair in
   the first line, in seconds.
3. Then delete `json-parse-bytes` from `examples/llama2/llama2.lisp` and call
   `rontolisp:json-parse` over the text again.

The WASM backends' strings are code-point arrays (`.kb/characters-code-points.md`), so
they are not affected -- which is also why the workaround must stay portable.
