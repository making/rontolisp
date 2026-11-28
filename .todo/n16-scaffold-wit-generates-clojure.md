# `--scaffold-wit` generates Clojure

Difficulty: Low

`--scaffold-wit world.wit -o impl.clj` is refused ("writes Common Lisp or Java"). A Clojure
author writes the `defn` per export and the `(rontolisp.wit/export "world.wit")` form by hand
(`doc/{en,ja}/clojure/reference/wit.md`).

- One `defn` per export, named by its label (a label keeps the WIT's spelling), throwing until
  filled in (`ex-info`), the `///` docs as the docstring, the WIT signature in a comment; the
  `export` form last, as the Lisp skeleton's directive is.
- An imported interface: the `(rontolisp.wit/import ...)` form, so a function can call it.
- Generate from the world as `ClojureHostBoundary` describes it, with
  `WitExportDirective.outline` for what to leave out, named as the Java skeleton names it
  (`cli/WitScaffolder.scaffoldJava`).
- Pin in `WitScaffolderTest`: the skeleton compiles to a component whose exports are the
  world's, as `aJavaSkeletonPassesTheCheckAndCompilesToTheWorld` does for Java.
- Doc: `doc/{en,ja}/guides/wit-contracts.md` ("Scaffolding an Implementation"), the Clojure
  `wit.md` reference, the CLI help in `CliOptionTable`.
