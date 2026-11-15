# j46. Starlark front end: the lowering and the seam

Difficulty: High

After `.todo/j45` (the reader). Port the prototype's lowering (`.todo/artefacts/j45-starlark-front-end-prototype/`, `sl2lisp.py`, table in
its `README.md`) to Java and register the language, so `prog.star` runs on the four
backends. Status EXPERIMENTAL, said on every user surface, as for Scheme and Clojure.

## Do

- `SourceLanguage.STARLARK`: `.star`, `--source-language starlark`; `forFile`,
  `isSourceFile`, `defaultExtension`; `CompileFrontend.run` refuses `--no-gc`
  (`.kb/source-language.md`). `eval -> starlark` joins the package graph
  (`PackageCycleTest`, `SourceLanguageSeamTest`).
- The lowering as measured: values, scoping (`block` + `let` of every assigned name),
  `for` as `tagbody`, the two call tiers (direct `defun` call, `%sl-call` binding),
  comprehensions, truthiness fused into tests.
- The run-time half as Common Lisp source spliced on demand, the `SchemeLibrary` way, with a
  spelling-only name gate (`.kb/source-language.md`, "The run-time libraries' name gate").
  Start from the prototype's `starlark-rt.lisp`.
- Every accessor behind a type check. The prototype found `(1, 2).append(3)` reaching a
  struct accessor of the wrong struct: an error with two different messages on the
  interpreter and the JVM, an uncatchable trap on WASM.
- Decide and write down in a new `.kb` file: the representation of `None`/`False` (the
  prototype used `:none` and `NIL`), identifier escaping, the string model (the spec leaves
  bytes vs UTF-16 units to the implementation), where `print` writes.
- User documentation in both `doc` trees in the same commit (`.kb/documentation-site.md`),
  one example in `examples/examples.yaml`.

## Test

`demo.star` of the prototype as a `ci-spec.yaml`-style case on the four backends, expected
output as recorded in the prototype's `README.md` run.
