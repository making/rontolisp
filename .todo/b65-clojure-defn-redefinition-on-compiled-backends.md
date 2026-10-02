# b65. A later `defn` of the same name wins everywhere on the compiled backends

Difficulty: Medium

Measured 2026-10-02 (four backends, `clj` 1.12.6.1673 oracle):

```clojure
(defn f [] 1)
(def g f)
(defn f [] 2)
(println (g) (f))
```

Oracle and interpreter print `1 2`; the JVM, wasm and the component print `2 2`. Each
`defn` lowers to a `defun` of the same mangled name, and `(def g f)` captures `#'c%f`; the
compiled backends resolve that designator to the program's one (last) definition, while the
interpreter captures the function object current when the `def` runs.

The corpus `life_without_multi.clj` redefines `my-print` three times and keeps each
version through `(def my-print-N my-print)`; its test file passes on the interpreter and
fails twice on the compiled backends.

## Plan

- Decide the shape: a redefined `defn` gets a fresh internal name per definition (the
  call sites below it call the newest), and `#'name` in a value position captures the
  definition current at that point; or values capture through a closure object.
- Pin the four-backend parity in `clojure-spec.yaml` with the shape above and the corpus
  `my-print-1/2/3` slice.
