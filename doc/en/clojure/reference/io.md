# IO

File entry points over the `eval` IO layer. They run on the interpreter and the
JVM -- there is no filesystem on wasm. `format` renders Java-format strings over
Clojure-notation arguments.

| Name | Example | Result |
|---|---|---|
| `spit` | `(spit path "a\n")` | `nil` |
| `slurp` | `(slurp path)` | `"a\n"` |
| `line-seq` | `(line-seq path)` | `("a")` |
| `format` | `(format "%s=%d" :a 5)` | `":a=5"` |
