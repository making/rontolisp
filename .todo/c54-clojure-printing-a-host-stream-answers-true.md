# c54. Clojure printing a stream prints `true`

Difficulty: Low

`(prn *out*)`, `(prn *in*)` and `(println (str *out*))` print `true` on the interpreter
(2026-10-03). The oracle (`clj` 1.12.6) prints `#object[java.io.OutputStreamWriter 0x... "..."]`
(and `str` the `toString`). Expected: an `#object[...]`-shaped spelling naming the
stream kind (no hash), the same on all four backends, like the unbound root's `#<Unbound: ...>`.
