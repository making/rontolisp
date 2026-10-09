# e93. Clojure: `blank?` and the trims miss Java's non-ASCII whitespace

Difficulty: Low

`clojure.string/blank?` (and the trims sharing `ClojureStringLowering.trimBag`) treat only
ASCII whitespace as blank, where the oracle asks `Character/isWhitespace`. Measured
2026-10-09: `(str/blank? " \t\n ")` and `(str/blank? "\u001c")` are false here, true
on clj 1.12.6; `(str/blank? " ")` is false on both (a no-break space is no Java
whitespace).

`clojure.lisp` has the predicate since 2026-10-09: `%clojure-xml-java-whitespace-p`
(clojure.xml's white-space-only text test).

## Plan

1. Measure `blank?`, `trim`, `triml`, `trimr`, `trim-newline` on the oracle over the Java
   whitespace set (9-13, 28-31, the Unicode space separators but U+00A0/U+2007/U+202F,
   U+2028/U+2029).
2. Share one predicate (rename it out of the xml section), lower the verbs over it.
3. clojure-spec case on all four backends.
