# f44. Clojure: `clojure.lang.LispReader$StringReader` as a reader function

Difficulty: Low

instaparse 1.5.0 reads a grammar's string literal with the reader's own string macro
(`cfg.clj:215`): `(let [string-reader (wrap-reader (clojure.lang.LispReader$StringReader.))]
(defn safe-read-string [s] (with-in-str s (string-reader *in* nil))))`, `wrap-reader` calling
it as `(reader r s {} (java.util.LinkedList.))` on Clojure past 1.6. Here the class does not
exist (`No such class: clojure.lang.LispReader$StringReader`). Found 2026-10-11 by removing
the stops before it by hand in a copy of the library.

Measured against clj 1.12.6:

| program | oracle |
|---|---|
| `(with-in-str "ab\\nc\"rest" ((clojure.lang.LispReader$StringReader.) *in* nil {} (java.util.LinkedList.)))` | `"ab\nc"` |

The macro reads past the opening quote up to the closing one, escapes decoded, and leaves
the rest of the stream unread.

## Plan

1. Lower the construction of the reader macro classes the run-time reader already has a
   string body reader for (`%clojure-rd-*` in `clojure.lisp` "Reading") to a function of the
   oracle's four arguments, reading from the stream; refuse the others by name.
2. Re-probe instaparse; pin on all four backends.
