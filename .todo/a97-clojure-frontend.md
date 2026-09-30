# Clojure frontend (experimental): promote the a97 spike to a real front end

Difficulty: High

## What exists

A feasibility spike, verified 2026-09-30: a Clojure subset read case-sensitively and
LOWERED to the Common Lisp core forms, exactly the Scheme front end's shape
(`.kb/scheme-frontend.md`). The spike's sources are parked, uncompiled, under
`.todo/artefacts/a97-clojure-frontend/src/main/java/am/ik/rontolisp/clojure/` (`Clojure.java`,
`ClojureReader.java`, `ClojureLowering.java`) -- move the directory back to
`src/main/java/am/ik/rontolisp/clojure/` to resume.

Verified end to end on 2026-09-30 with `demo.clj` (recursion with mutual
reference, `loop`/`recur`, `#(...)`, higher-order `map`/`filter`/`reduce`, vector and
keyword literals, `cond`/`:else`, `let`, a directly called `fn`): every form answered the
same value on the interpreter AND on the JVM-compiled class, and the seam tests
(`SourceLanguageSeamTest`, `RontoLispCliTest`, `JvmSourceCompilerTest`, 108 tests) stayed
green with the seam change in place. The verification program lives beside the sources
(`.todo/artefacts/a97-clojure-frontend/demo.clj`).

## The seam diff the spike carried

`eval/SourceLanguage.java` needs, beside the existing Scheme arm:

```diff
@@ enum constants @@
 	SCHEME(".scm"),
+
+	/**
+	 * Clojure, EXPERIMENTAL SPIKE: a small subset read case-sensitively and lowered to the
+	 * same core forms by the {@code clojure} package. A feasibility probe; no subset or
+	 * compatibility promise yet.
+	 */
+	CLOJURE(".clj");

@@ readProgram, beside the SCHEME arm @@
 		if (this == SCHEME) {
 			return refuseCircularLists(Scheme.read(source, file, standards.scheme(), schemeFiles(loader)), source,
 					file);
 		}
+		if (this == CLOJURE) {
+			return refuseCircularLists(Clojure.read(source, file), source, file);
+		}

@@ forFile, beside the SCHEME extension check @@
 		if (path != null && path.endsWith(SCHEME.defaultExtension())) {
 			return SCHEME;
 		}
+		if (path != null && path.endsWith(CLOJURE.defaultExtension())) {
+			return CLOJURE;
+		}

@@ isSourceFile @@
 	public static boolean isSourceFile(String path) {
 		return path.endsWith(COMMON_LISP.defaultExtension()) || path.endsWith(SCHEME.defaultExtension())
-				;
+				|| path.endsWith(CLOJURE.defaultExtension());
 	}

@@ parse, beside the scheme names @@
 		if (name.equals("scheme") || name.equals("scm")) {
 			return SCHEME;
 		}
+		if (name.equals("clojure") || name.equals("clj")) {
+			return CLOJURE;
+		}
```

(`import am.ik.rontolisp.clojure.Clojure;` at the top.) `SourceSession`, the REPL and
the playground are NOT covered by the spike -- see "Deliberately out" below.

## What the spike lowers, and the deliberately accepted simplifications

The lowering table as built (`ClojureLowering`): `defn` -> `DEFUN` (direct call, tree
shaker), `def` -> top-level `SETQ`, `fn` / `#(...)` -> `LAMBDA` (`#(...)` arguments in
one `&REST` list, `%`..`%9` as `(NTH n args)`), `let` -> `LET*`, `loop`/`recur` ->
`LABELS` self call (constant stack through the interpreter's tail calls), `if`/`when`/
`cond`/`do`/`and`/`or` -> the core forms, `map`/`filter`/`reduce`/`apply`/`concat` ->
`MAPCAR`/`REMOVE-IF-NOT`/`REDUCE`/`APPLY`/`APPEND`, a vector literal -> a `VECTOR` call,
identifiers mangled behind `c%` (always, so no name can reach a `LispNames` case label,
a lambda-list keyword or `T`/`NIL`), every core-form head and built-in name emitted
UPCASE, `true` the `LispTrue` singleton (a raw symbol spelled `T` is unbound --
`evalSymbolRef` looks the name up), `nil`/`false` `NIL`.

Simplified FOR THE SPIKE; each is a real work item when this is promoted:

- `false` is folded into `nil`. Both are falsey so tests behave, but `(false? x)` cannot
  distinguish them and `(= false nil)` is true. Clojure-distinct booleans need the
  `#f`-style distinct-object treatment `scheme.lisp`'s false value uses
  (`.kb/scheme-frontend.md`), with its `symbol?`/truthiness exemptions spelled.
- Keywords are the symbols `:foo` verbatim and the printer upcases them (`:A` prints
  for `:a`), so `:a` and `:A` collide and printed keywords are the wrong case. Needs a
  keyword decision: a mangled unique spelling plus printer support, or accepting
  upcasing.
- Map and set literals are refused by name (`a map literal is not supported yet`); a
  map needs the hash-table runtime (`.kb/hash-tables.md`) and a decision about what
  `{:a 1}` IS (Clojure maps are persistent, structurally equal, function-like -- none of
  which a CL hash table is).
- The seq family runs over LISTS only (`first`=`CAR`, `rest`=`CDR`, `count`=`LENGTH`
  being the exceptions that take any sequence). Clojure's vectors-as-seqs need real
  design (`nth` performance, `subvec`, `into`, `range`, `map` over mixed collections).
- `#(...)` takes at most 9 args and the body's forms are wrapped as ONE call
  (`#(f a b)` -> `(f a b)`, matching the dominant spelling; multi-form bodies with
  explicit `do` are unrepresentable). Clojure's reader wraps in `do`.
- Destructuring (`let [{:keys [a]} m]`, `[a & rest]` in `fn` param vectors beyond a
  trailing `& rest`), `->`/`->>`, `defmulti`/`defmethod`, protocols, `atom`/`swap!`/
  `deref`, lazy seqs, metadata `^`, `var`/`#'`, `#(...)` arg >9: all absent. The reader
  parses `@x`, `^meta`, `` ` ``, `~` into marked lists the lowering refuses.
- `defn` inside a body works only in statement position and registers the global
  lowering-time (a later form may call it; Clojure would too); `declare` is absent.
  `def` inside a body mutates the global at run time, unreviewed.
- `apply` is the 2-arity only (`(apply f args)`); Clojure spreads `(apply f a b args)`.
  `reduce` is 2/3-arity with the Clojure argument order (`(reduce f val coll)`) mapped
  onto CL `REDUCE` `:initial-value`.
- Errors carry no source position: the reader's `LispReadException`s are position-less
  (the plumbing for `file:line:column` exists -- `.kb/source-positions.md` -- the spike
  just never threads the reader's line/column into the lowering's errors).
- Printing: `pr`/`prn`/`print-method`/`pprint` are absent; `println` is one
  `CONCATENATE` + `PRINC` (so a string prints unquoted, Clojure's `println` agrees, but
  collections print in CL notation `#(A B)` / `(1 2 3)`).

## What a promotion must also carry (project rules, not code)

- The `.kb` pair: a `clojure-frontend.md` beside `scheme-frontend.md` (lowering table,
  the mangle, the deviations) and a `source-language.md` / `doc/en` + `doc/ja` update
  per user-facing surface; `--source-language` help text names it experimental.
- `SourceLanguageSeamTest` exemptions if any new direct reader call appears (there are
  none today: the spike reads only through the seam).
- A pinning test per `.kb/adding-primitives.md`'s spirit: the interpreter, the JVM and
  both WASM backends on one corpus (the demo above is the seed), before ANY behavior is
  promised. `--no-gc` refusal follows `CompileFrontend.run`'s SCHEME arm.
- REPL/session: `SourceSession` for Clojure (`isComplete` needs bracket counting over
`#{[(`), or an explicit "no REPL yet" scope statement.
- The `spring-javaformat` + NullAway conventions the spike already satisfies were
  enforced by the build, not by review -- re-run `./mvnw spring-javaformat:apply
  test` on the whole reactor after the move back.
