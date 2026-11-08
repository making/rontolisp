# Clojure: `io/reader` and `slurp` refuse a `java.io.Reader` the program constructed

Difficulty: Low

Found by the j07 probe round:

- data.csv 1.1.1: the README's own idiom `(with-open [r (io/reader ...)] (doall (csv/read-csv r)))`
  over a `java.io.StringReader` -- the one probe line of six that fails; `read-csv` of a string
  and `write-csv` into a `java.io.StringWriter` print the oracle's lines.
- aero 1.1.6: `(aero/read-config (java.io.StringReader. "{:a #profile {...}}") opts)` -- aero's
  documented way to read a config from a string (`read-config-into-tagged-literal` calls
  `io/reader` on its source).

## Repro (2026-11-08, develop `a3c68a63f`)

```clojure
(require '[clojure.java.io :as io])
(prn (slurp (java.io.StringReader. "abc")))
(prn (line-seq (io/reader (java.io.StringReader. "a\nb"))))
```

- oracle: `"abc"` then `("a" "b")`
- interpreter and JVM class: `Unhandled condition: Cannot open <#<java java.io.StringReader>> as
  an InputStream.` (from `clojure.lisp`'s `IOFactory` arms)

Measured beside it: `(line-seq (java.io.BufferedReader. (java.io.StringReader. "c\nd")))` works
(`("c" "d")`), and so does `(read (java.io.PushbackReader. (java.io.StringReader. "{:a 1}")))`;
so a host `Reader` object is readable here, only `IOFactory`'s `make-reader` has no arm for it.

## Direction

The oracle's `IOFactory` extends `java.io.Reader` (`make-reader` answers it wrapped in a
`BufferedReader`, `make-input-stream` refuses) and `java.io.Writer` (`make-writer`). Add the
`Reader`/`Writer` host-object arms beside the File/URL/byte-stream arms (KB "`clojure.java.io`":
"portable File/URL/URI and byte-stream values behind one arm family"); `slurp` of a reader goes
through `make-reader`. On wasm `java.io.StringReader.` is a host construction -- check what
`(java.io.StringReader. s)` lowers to there (a portable string reader value?) and make the same
two lines work, or keep the call-time refusal and say so in the KB.

## Done when

- The repro prints the oracle's lines on the interpreter and the JVM, and on wasm if a
  `StringReader` exists there (ci-spec case; `io/writer` of a `java.io.StringWriter` too).
- data.csv's probe is `=` verbatim (j07 `data-csv/`), aero's probe lines 3-4 with
  `StringReader` sources print the oracle's lines.
