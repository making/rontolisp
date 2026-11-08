# Clojure: specter's `riddley.walk/macroexpand-all` -- riddley drives the Clojure compiler's internals

Difficulty: High

specter 1.1.6 requires `riddley.walk` (riddley 0.1.12) on the `:clj` branch
(`com/rpl/specter/impl.cljc:17-19`) and calls `riddley/macroexpand-all` from its `path` macro
(`impl.cljc:66-71`, `clj-macroexpand-all`), so the paths a `select`/`transform` call names are
macroexpanded with `&env` locals tracked. Loading `riddley.compiler` stops at once
(2026-11-08, develop `a3c68a63f`):

`riddley/compiler.clj:12:3: proxy constructor arguments are not supported yet:
(|%vector| (|Compiler$ObjExpr.| |nil|) |nil|)`

riddley is the Clojure compiler's own machinery used from outside (`riddley/compiler.clj`):
`(proxy [Compiler$ObjMethod] [(Compiler$ObjExpr. nil) nil])`, `Compiler/LOCAL_ENV`,
`Compiler/CLEAR_SITES`, `Compiler/METHOD` as dynamic vars bound with `with-bindings`, a Java
helper `riddley.Util/localBinding` building `Compiler$LocalBinding`s, `@Compiler/LOCAL_ENV` as
the `&env` map. Its `walk.clj` then macroexpands with `macroexpand-1`, handling the special forms
(`let*`, `loop*`, `letfn*`, `fn*`, `case*`, `reify*`, `deftype*`, `catch`, `.`) so it knows where
locals are bound.

specter itself has an escape hatch: under babashka it requires `[clojure.walk :as riddley]`
instead (`#?(:bb [clojure.walk :as riddley] :clj [riddley.walk :as riddley])`). With that swap
(and the other j07 patches) the probe printed the oracle's 10 lines, i.e. `clojure.walk/
macroexpand-all` was enough for those paths. The difference matters only when a path holds a
macro whose expansion depends on `&env` (riddley's reason to exist).

## Options

- a. Make riddley's namespaces load and work: `Compiler$ObjExpr`/`Compiler$ObjMethod` as
  constructible stand-ins, `Compiler/LOCAL_ENV` & co. as dynamic vars whose `LOCAL_ENV` value is
  the lowering's `&env` map, `Compiler$LocalBinding` values (KB: `&env` values answering
  `Compiler$LocalBinding`'s `.hasJavaClass`/`.getJavaClass` is already step 3 of `.todo/g87`,
  which needs the same thing for tools.analyzer.jvm). Proxy constructor arguments are a general
  gap of their own (`proxy [C] [args]`): measure how many libraries pass them.
- b. Ship a built-in `riddley.walk` (like the built-in `clojure.walk`/`clojure.zip` sources,
  KB "the clojure.jar namespaces shipped as Clojure source") implementing `macroexpand-all` and
  `walk-exprs` over the lowering's own `&env`. A shipped replacement of a third-party
  library must be named in the user docs (the deps.edn's riddley jar would be shadowed).
- c. Claim nothing; specter stays red.

The `&env` contract is shared with g87 (core.async's `go`); decide them together.

## Done when

- The choice is made with the measurements written into `.kb/clojure-frontend.md` (what each
  option costs; how many of the KB census's libraries require riddley: potemkin's
  `potemkin.walk`? manifold? measure via the `~/.m2` jars).
- specter's probe (j07 `specter/`) prints the oracle's lines verbatim on the interpreter and the
  JVM after j12, j13, j15, j17, j21, j22, j23, j25, or its KB row names what still stops it.
