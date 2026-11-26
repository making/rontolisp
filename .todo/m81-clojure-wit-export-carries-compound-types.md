# m81. Clojure `rontolisp.wit/export` carries compound types

Difficulty: Medium

A Lisp world's export carries records, variants, enums, lists, options, tuples and results on a
component (`.kb/wit.md`, "Compound types across an export"), and so does a Java world's
(`.kb/java-frontend.md`, "WIT exports"). A Clojure world's does not:
`WitExportDirective.describe` answers each export's whole shape, and
`ClojureHostBoundary.exportWorld` refuses one marked `canonical` ("the Clojure tier's export
boundary does not support yet"); `doc/{en,ja}/clojure/reference/wit.md` says "a world's exports
carry the integers, `f64`, `bool` and `string`".

- **The mapping is the import tier's** (`.kb/clojure-frontend.md`, "Host boundary";
  `doc/en/clojure/reference/wit.md`'s table): a record a map of keywords, a list a vector, a
  `list<u8>` a byte array (`:octets t`, which the directive now takes), a `result` as the tier
  reads its envelope. The export's wrapper (the multi-arity `defn` one the lowering writes per
  export, `.kb/wit.md`, "Two exports of one label") converts the Lisp shape in and the Clojure
  value out.
- **To make**: the refusal lifted, the tier's refusals for an export (what `feed` refuses for
  an import), the conversion, and how a var signals the error arm of a top-level `result`
  (`<f>%wit-export` catches `rontolisp:wit-error`; `ex-info` is the tier's condition; a wrapper
  answering the envelope itself says `:envelope t`, as Java's do).
- Pins: `ClojureWitBoundaryTest.aWorldIsImplementedAsAComponent` and its refusal row
  (`points.wit`), `ClojureHostBoundaryTest`,
  `examples/clojure/greeter`; the Clojure reference page in both languages.
