# c71. Clojure `class` of a stream refuses

Difficulty: Low

`(class *out*)`, `(class (java.io.StringWriter.))` and `(class (clojure.java.io/reader f))`
signal `class needs a value of a known kind` on all four backends (measured 2026-10-04;
`(class *out*)` answered `:boolean` while `*out*` was the `t` designator). The oracle
answers the host class (`java.io.OutputStreamWriter`, `java.io.StringWriter`,
`java.io.BufferedReader`). The printer already names that class per stream kind
(`%clojure-stream-class` in `clojure.lisp`, `.kb/clojure-frontend.md` "Streams as
values"); `class` answers kind keywords for Clojure values, so the answer has to fit that
model (and `defmulti` dispatch on `class`). Expected: a `class` arm for a stream, gated
like the printer's (`ClojureArms.Family.STREAM`), pinned in clojure-spec.
