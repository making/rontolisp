# with-out-str

`(with-out-str body...)`

Runs the body with `*standard-output*` bound to a fresh string stream and
answers what it printed. Never a literal `with-output-to-string` (which would
flip a WASM module into EH mode): the stream is built and read back directly,
like `str`. `*out*` is `*standard-output*`, so `(. *out* write ...)` lands in
the same capture. A zero-argument `(new java.io.StringWriter)` is the same
string stream on every backend (WASM included), so the oracle's own
`with-out-str` macro -- rebinding `*out*` around the body and reading the
writer back with `str` -- runs as written; `str`/`.toString` answer the text
so far without clearing it.

```clojure
(println (with-out-str (print 1) (pr :a))) ; 1:a
```
