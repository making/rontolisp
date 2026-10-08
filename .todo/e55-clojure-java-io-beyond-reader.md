# e55. Clojure: `clojure.java.io` beyond `reader`

Difficulty: High

`clojure.java.io` is the clojure.jar namespace libraries require most after
`clojure.string` (20 of 72 jars, `.kb/clojure-frontend.md` "clojure.jar namespaces"); only
`reader` resolves here. Var use across the same corpus (measured 2026-10-08, a library
counted once per var): `file` 14, `reader` 8, `copy` 7, `input-stream` 7, `resource` 7,
`make-parents` 3, `writer` 2, `as-file` `delete-file` `make-input-stream` `IOFactory` 1.

## Why it is not a namespace file

- `file` answers a `java.io.File`. Interop is interpreter- and JVM-only (`java:` is a
  call-time error on wasm), while `reader`/`slurp`/`spit` run on every backend over WASI
  preopens. A portable answer needs a File value of this front end's own (path, parent,
  name, `exists`/`isDirectory`/`mkdirs`/`delete`/`listFiles`, printing as the oracle's
  `#object[java.io.File ...]`), read by `reader`/`slurp`/`spit` and by `.method` calls.
- `input-stream`/`output-stream`/`copy` move bytes: a byte stream kind on every backend
  (the WASI file API is byte-oriented; the character streams decode UTF-8 today).
- `resource` answers a class-path URL; here the natural source is the source path
  (`ClojureSourcePath`, jar roots included), which needs a URL-like value too.

## Plan

1. Decide the File and byte-stream representation across the four backends.
2. Ship the namespace (lowerings or Clojure source over kernels) with the coercion protocols
   (`Coercions`, `IOFactory`) a library extends.
3. clojure-spec lines (wasm with a `--dir` preopen); `doc/*/clojure/reference/io.md`.
