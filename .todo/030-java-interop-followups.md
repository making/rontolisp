# Java interop follow-ups

The `java:` interop package (interpreter-only reflection bridge) was adopted into
`develop`: `LispJavaObject` + the `java` package (`java:new`/`call`/`static`/
`field`/`proxy`) in `eval/JavaInterop.java`, with deterministic cost-based
overload resolution, `JavaInteropTest`, docs (`doc/{en,ja}/guides/java-interop.md`
+ five `reference/functions/java-*.md`), and examples
`examples/jvm/java-interop.lisp` / `examples/jvm/swing.lisp` /
`examples/console/life-core.lisp` / `examples/jvm/life-gui.lisp` (the console
`examples/console/life.lisp` was split to load the shared core). See the
CLAUDE.md "java: interop" bullet.

Deliberately deferred (scope was kept to one clean GUI demo; the existing
`maze-rl.lisp` / `nqueens.lisp` were left untouched):

- **More GUI demos.** A second Swing demo landed since: commit 440056c added
  `examples/browser/minesweeper/minesweeper-swing.lisp`. Still on the `gui-poc`
  branch: `maze-rl-gui.lisp` / `maze-rl-core.lisp` and `nqueens-gui.lisp` /
  `nqueens-core.lisp` (same core/gui split pattern as life). Bring them over if
  more Swing examples are wanted, but note 440056c also reorganized `examples/`
  into category subdirs while `gui-poc`'s paths are still flat
  (`examples/maze-rl-gui.lisp`), so a bare
  `git checkout gui-poc -- examples/<file>` lands them at the top level and they
  must then be relocated (Swing demos to `examples/jvm/`, their cores next to the
  console programs in `examples/console/`). Update the headers the same way
  `life-gui.lisp`/`swing.lisp` were (file-relative `load` works now, so drop the
  "run from inside examples/" caveat), add README rows, and add a row to
  `examples/examples.yaml` -- the smoke-test index that `ExamplesE2eTest` turns
  into one dynamic test per (example x backend) pair.

- ~~**Friendlier compile error.**~~ OBSOLETE for the JVM (2026-07-02): the JVM
  compiler now supports the five `java:` functions natively via an embedded
  bridge (`codegen.jvm.JavaBridgeTemplate` rewritten against the compiled value
  representation, base64-embedded + `Lookup.defineClass`'d by the emitted
  `_javaInit`; see the CLAUDE.md bullet and `JvmJavaInteropCompilerTest`). The
  WASM backend still fails with the generic `Cannot compile: java:new`; a
  special-cased "the java interop package needs a JVM backend" message there
  remains low value.

- **First-class `java:` functions in compiled code.** The compilers have no
  `BuiltinFunctionWrappers` entries for the five functions, so `#'java:call` /
  `(funcall 'java:new ...)` is a compile error while the interpreter allows it;
  the embedded `eval` runtime has no `java:` dispatch either. Variadity is NOT
  the blocker -- the wrapper scheme already expresses `&rest`
  (`variadicIdentity`/`variadicNonEmpty`/`variadicNconc`/`variadicUnaryLeft`/
  `variadicMakeArray`/`variadicStableSort`, plus the `(&rest r)` entry for
  `values`); the entries were simply never written. Documented in the guide's
  limitations. Would need wrapper entries for the five functions plus `java:`
  dispatch in the emitted `eval` runtime.

- ~~**Richer marshalling (optional).**~~ DONE (2026-07-02): proper lists /
  rank-1 vectors now marshal to Java arrays (element-wise, incl. primitives)
  and `List`/`Collection`/`Iterable` params; varargs tails are packed (flat
  cost penalty, fixed-arity preferred); Java array results unmarshal to Lisp
  lists; methods on JDK-internal classes (e.g. `List.of` results) re-resolve
  to an accessible interface declaration. Conversion rules documented in the
  guide (both languages).

- **Native-image reflection config (optional).** Interpreting interop in the
  native binary is unusable because arbitrary `Class.forName` is not registered
  (the native binary CAN compile a `java:` program to a `.class` since
  2026-07-02 — the bridge template bytes are a registered resource). A curated reflect-config could enable a fixed allow-list of classes
  in the native binary, but Swing/AWT in a native image is its own (largely
  experimental) problem — out of scope unless specifically requested.
