# c54. Clojure printing a stream prints `true`

Difficulty: Medium

`(prn *out*)`, `(prn *in*)` and `(println (str *out*))` print `true` -- on all four
backends, not only the interpreter (measured 2026-10-03). The oracle (`clj` 1.12.6) prints
`#object[java.io.OutputStreamWriter 0x... "..."]` (and `str` the `toString`). Expected: an
`#object[...]`-shaped spelling naming the stream kind (no hash), the same on all four
backends, like the unbound root's `#<Unbound: ...>`.

Cause: `*standard-output*` and `*standard-input*` hold `t` at the root on every backend
(`StreamDesignators.standardStreamDefaults`), the same object as Clojure `true`, so no
printer arm can tell them apart. A real `%STREAM` (`*err*`, a string stream) prints
`#<STREAM>` (the CL fallback) and can get its arm directly.

Plan: a stream value for the process standard output/input (a `%STREAM` over handle 1/0,
like `*error-output*`'s over 2) that a Clojure program's `*out*`/`*in*` hold at the root,
with every backend writing/reading through it; then the printer and `str` arms for a
`%STREAM` by kind (output/input, string/file/standard). Measure what the root change costs a
program that only prints.
