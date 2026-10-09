# e96. Clojure: a resource a jar holds, found by a name computed at run time

Difficulty: High

`clojure.java.io/resource` of a literal name finds a jar's entry while the program lowers
and embeds its text; a name computed at run time is looked up below the directory roots
only (`%clojure-io-resource`), so it never finds a jar's entry. Ring's `resource-response`
always computes its name (`(str (:root options) path)`), so a jar's static files (webjars,
a library's `public/`) are never served: measured 2026-10-09, `(resource-response
"jarres/j.txt")` is the jar entry with `Content-Length` and the jar file's `Last-Modified`
under clj 1.12.6 + ring-core 1.15.5, `nil` here on all four backends
(`ClojureRingFileResponseTest`'s last line). Related: a literal name naming a directory is
`nil` here where the oracle answers the directory's URL.

Embedded text is also not binary-safe: a jar's image comes back decoded as UTF-8.

## Plan

1. Decide per backend: the interpreter and the JVM can open the jar when the program runs
   (`java.util.zip`); wasm can read the jar through a preopen only with an inflater in the
   runtime, or the lowering embeds the entries below a prefix the program names literally
   (`resource-response`'s `:root`), as octets.
2. Keep entries as octets, not text, so `url-response` serves them byte for byte.
3. A directory's URL for a literal name, so `url-response` of it answers `nil` like the
   oracle's instead of signalling on `nil`.
