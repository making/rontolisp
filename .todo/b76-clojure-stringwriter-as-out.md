# b76. `(new java.io.StringWriter)` bound to `*out*`

Difficulty: Medium

The oracle's own `with-out-str` (the shcloj4 `data/snippets/macros.clj` excerpt) is

```clojure
(defmacro with-out-str [& body]
  `(let [s# (new java.io.StringWriter)]
     (binding [*out* s#] ~@body (str s#))))
```

Since b63 a program macro of a core name expands, and `new` in the expansion lowers at the
call site (`java:new`). Measured 2026-10-02 on the interpreter: the binding fails with
`not an output stream: #<java java.io.StringWriter>` -- `*out*` is `*standard-output*`,
and no backend writes to a host `Writer`. On wasm `java:new` is refused outright.

## Plan

Lower a zero-argument `java.io.StringWriter` construction to a Common Lisp string output
stream, so every backend runs it: `.write`/`.flush`/`.close` already dispatch on
`streamp` (`ClojureInteropLowering.streamMethod`). Missing: `str` / `.toString` of it must
answer the text so far WITHOUT clearing it (`get-output-stream-string` clears; write the
text back, or keep a runtime registry of the streams a `StringWriter` made -- there is no
portable string-stream predicate, `.kb/read-load-streams.md`). `pr-str` of it is the
oracle's `#object[java.io.StringWriter ...]` (unpinnable).

## Oracle

```bash
clj -M -e '(let [s (new java.io.StringWriter)] (binding [*out* s] (print 1) (print 2)) (println (str s) (str s) (.toString s)))'
```

prints `12 12 12`.

## Acceptance

A `clojure-spec.yaml` case (all four backends) running the excerpt's `with-out-str`.
