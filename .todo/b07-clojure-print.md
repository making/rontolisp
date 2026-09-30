# b07: Clojure print wrapper (Clojure notation via a spliced library)

Difficulty: Medium

## Premise (measured, 2026-09-30)

Clojure values print in CL notation (`.kb/clojure-frontend.md`, "Deviations"):
vectors as `#(1 (C%KEYWORD a) s)`, maps as `#<HASH-TABLE :TEST EQUAL :COUNT n>`,
sets as `(C%SET #<HASH-TABLE ...>)`, nested keywords as `(C%KEYWORD a)`,
nested `true`/`nil` as `T`/`NIL`, quoted symbols as `c%foo`, chars as `#\a`.
The spike (`.todo/artefacts/b07-clojure-print/`, `NOTES.md`) proves a wrap
works: a ~200-line portable-CL printer rendering `[1 :a s]`, `{:a 1}`,
`#{1}`, `(true false nil :k)`, `e2e-foo`, `\a`/`a`, readable strings --
byte-identical on SBCL 2.2.9 and on all four backends (interpreter, JVM,
wasm Preview 1, wasm component) against REAL lowered values, including the
real `rontolisp::%clojure-false` object. No backend learns a Clojure name,
the same invariant `scheme.lisp` holds (`.kb/architecture.md`).

## Shape

- `src/main/resources/am/ik/rontolisp/eval/clojure.lisp` + `eval/ClojureLibrary`
  (the `SchemeLibrary` shape: `forms`, `isClojureFunction`, `process`): the
  printer as `%clojure-` defuns over the existing value model (keyword/set
  wrappers, `equal` tables, `c%` mangle). `println`/`print`/`pr`/`prn` write
  to the stream DIRECTLY (no `with-output-to-string`: a literal one flips a
  WASM module into EH mode -- measured gate, see spike `NOTES.md` finding 6);
  string building stays in `str` (and a new `pr-str` if wanted).
- Lowering (`clojure/ClojureLowering`): `printCall`/`prCall`/`strCall`/
  `printPartBody`/`keywordOrString` route through the library; the `str`
  function value too. Each bullet independently shippable: scalars/keywords
  first (no new runtime shape), then vectors/lists, then maps/sets, then
  readable arms, then the REPL echo.
- `eval/SourceSession.echo`: the Clojure arm prints through the library
  (Scheme's `ECHO` shape), so the `clojure> ` echo stops showing wrappers.
- Cycles need Scheme-scale datum labels, not the spike's depth cap + Floyd
  `.#` (the naive walk exhausted 500 MB on SBCL before Floyd; car/element
  cycles are still cap-only in the spike). Decide against `%scheme-print`'s
  design, shared or copied.
- `nil` stays `nil` (it IS the empty list; never `()`), map/set walk order
  stays unspecified (same as `keys`/`vals`), unreadable fallback stays
  `#<..>` -- all documented deviations, not gaps.
- Open decisions: honor `*print-length*`/`*print-level*` inside the wrapper
  or document the loss (a routed `println` bypasses `%print-cased`);
  `~S`/`~A` on Clojure values (out of scope: `format` is a CL surface);
  `print-method`/`pprint` stay absent.

## Tests

- `clojure-spec.yaml` expectations move to the new notation
  (`[1 :a s]`, `{:a 1}`, `#{1}`, `(true false ...)`, `e2e-foo`-style symbols,
  `\a` chars); `ClojureSpecE2eTest` on all four backends.
- `ClojureLoweringTest` (refusals unchanged, call shapes), `RontoLispCliTest`
  (the `clojure>` REPL transcript re-pinned), `PackageCycleTest` (the
  `clojure` package still sees only AST types + `reader`; the `.lisp`
  resource is data).
- User-facing behavior mirrored in `doc/en/**` + `doc/ja/**` in the same
  commit (`.kb/documentation-site.md`); `.kb/clojure-frontend.md`
  "Deviations" rewritten as each bullet lands.
