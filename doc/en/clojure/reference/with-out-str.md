# with-out-str

`(with-out-str body...)`

Runs the body with `*standard-output*` bound to a fresh string stream and
answers what it printed. Never a literal `with-output-to-string` (which would
flip a WASM module into EH mode): the stream is built and read back directly,
like `str`. `*out*` is `*standard-output*`, so `(. *out* write ...)` lands in
the same capture.

```clojure
(println (with-out-str (print 1) (pr :a))) ; 1:a
```
