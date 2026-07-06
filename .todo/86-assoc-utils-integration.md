# assoc-utils integration (real library load via asdf:load-system)

**Status:** IN PROGRESS -- handed off for a fresh session (2026-07-06).
Target: assoc-utils (Eitaro Fukamachi, **Public Domain**, 2016). Single file
`src/assoc-utils.lisp`, `:depends-on ()`. The cleanest remaining ASDF candidate:
every gap is ADDITIVE (no cross-cutting semantic change, unlike cl-base64's
symbol-name issue in `.todo/85`).

## HOW TO RESUME (self-contained -- scratchpad is gone in a new session)

The library source is **vendored in-repo** at `src/test/resources/assoc-utils/`
(`assoc-utils.asd`, `src/assoc-utils.lisp`, `README.markdown`, plus
`HANDOFF-driver.lisp`). Reproduce the current first error with:

```bash
./mvnw -q clean spring-javaformat:apply package -DskipTests
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar \
  src/test/resources/assoc-utils/HANDOFF-driver.lisp \
  --system-path src/test/resources/assoc-utils
```

Current output (the .asd parses; first missing feature):
```
warning: skipping unsupported #. read-time-eval form
... The function assoc-utils::define-setf-expander is undefined
```
(The `#.` warning is expected and harmless -- see fix #3 below.)

Upstream (if you need to re-fetch or diff):
`http://beta.quicklisp.org/archive/assoc-utils/` or GitHub
`fukamachi/assoc-utils` master (`.../archive/refs/heads/master.tar.gz`).

## ALREADY DONE in the prior session (UNCOMMITTED in the `develop` working tree)

Three general fixes landed; **full suite 2829 green**. NOT yet committed -- the
new session should keep them (they underpin this integration and are correct on
their own):

1. **`.asd :name` option tolerated** -- `AsdfSystems.parseDefsystem` (assoc-utils.asd
   has no `:name`, but this came from the cl-base64 attempt; harmless, keep).
2. **`~<newline>` line-continuation format directive** -- `LispMacroExpander.FmtParser.dispatch`
   (from cl-base64; not needed by assoc-utils but correct).
3. **`#.` read-eval skip leaves a `nil` placeholder** [needed by assoc-utils.asd]:
   the lexer previously dropped a skipped `#.` datum entirely, desyncing the
   following `:key value` pairs of `defsystem` (`assoc-utils.asd`'s
   `:long-description #.(with-open-file ...)` triggered "expects :option value
   pairs"). Now `LispLexer` emits a `nil` SymbolToken after `skipDatum()`, and
   `AsdfSystems.parseAsdSource` `continue`s on a bare top-level `LispNil`.
   Pinning tests updated: `LispReaderTest.readReadEvalSkippedInTolerantMode`
   (`[nil, 42]`), `AsdfSystemsTest.parseAsdSourceSkipsAReadEvalGuardWithAWarning`.
   `readAllSkippingReadEval` is only reached from `.asd` parsing, so this is
   scoped.

## REMAINING BLOCKERS (in load order; each confirmed by running the interpreter)

Follow the repo's per-feature workflow: interpreter -> JVM -> WASM -> component;
`.kb/*.md` for the mechanics; add ci-spec + docs. Suggested implementation order
(easiest/most-general first) is 2 -> 4 -> 3 -> 1.

1. **`define-setf-expander`** (undefined) -- CURRENT first error. Used once:
   `(define-setf-expander aget (alist key &optional default &environment env) ...)`
   with `get-setf-expansion` + `&environment`, so `(setf (aget alist key) v)`
   works. Full setf-expansion protocol is heavy.
   - **Lite path (recommended first):** make `define-setf-expander` a parsed no-op
     (like the lite `define-condition`) so the file loads, AND separately register
     `aget` as a setf place that expands to the library's own `%aput`
     (`(setf (aget a k) v)` => `(setq a (%aput a k v))`). If a proper place is too
     much, a pure no-op (dropping `(setf (aget))` support) still unblocks the whole
     read API -- document it as a lite limit.
2. **`define-modify-macro`** (undefined) -- 3 top-level uses:
   `(define-modify-macro delete-from-alist  (&rest keys) remove-from-alist)` and
   likewise `delete-from-alistf`, `remove-from-alistf`. Clean `LispMacroExpander`
   lowering: `(define-modify-macro NAME (params) FN)` =>
   `(defmacro NAME (place . params) ... (setf place (FN place params...)))`
   using the existing setf place machinery + a gensym for the place-once idiom.
   Standard CL, generally useful beyond this lib. Register in `LispNames` +
   `PackageRegistry.CL_SYMBOLS` + evaluator/both compilers (macro dispatch).
3. **`loop ... being the hash-keys of H using (hash-value V)`** iteration clause --
   in `hash-alist`. rontolisp's loop has no `being the {hash-keys,hash-values}`
   driver. Add to the loop expander; needs a runtime hash-iteration primitive on
   all backends (interpreter has real HashMap; WASM open-chaining table -- see
   `.kb/hash-tables.md`). Medium.
4. **`mapl`** (verify -- likely missing): `alistp` does
   `(mapl (lambda (tree) ... (return-from alistp nil)) value)`. Also verify
   `mapcan` (alist-plist) and `mapcar #'rec-conv` exist. `mapl` = like `mapc` but
   passes successive cdrs (sublists), returns the list. Add as a builtin
   (`Environment` + JVM/WASM + `BuiltinFunctionWrappers`) if absent.

Confirmed already-OK forms (no work needed): `deftype ... (satisfies alistp)`,
`check-type`, lite `return-from alistp`, `sort :key`, `copy-seq`, `equalp`,
`make-hash-table :test #'equal`, `setf gethash`, `dolist`, `list*`, `intern` +
`string-upcase` + `format nil`, `keywordp`, `nthcdr`/`elt`, `reduce
:initial-value`, `with-keys` (a plain defmacro), `remove-if`, `typecase`.

## FINISH-LINE CHECKLIST (from the proven workflow -- do not skip)

- Verify on ALL FOUR backends (interpreter / JVM `-o Prog.class` / WASM
  `wasmtime run -W gc` / `--component`). The COMPILE path statically resolves
  every defun body, so all files must compile even if a fn is never called.
- Vendor stays under `src/test/resources/assoc-utils/` (keep README; it's Public
  Domain so no LICENSE file, but note the author/PD status in the test).
- Add `AssocUtilsE2eTest` next to `SplitSequenceE2eTest`/`ClWhoE2eTest`
  (interpreter + compiled JVM), plus plain-Lisp `ci-spec.yaml` residue cases for
  each NEW feature (define-modify-macro, loop-hash-keys, mapl) -- NOT for the
  asdf load itself (ci-spec can't provide the .asd on disk).
- New builtins/macros shift introspection counts: update `hasSize(N)` +
  macro-list strings in `LispEvaluatorTest` + `JvmLispCompilerTest` +
  `WasmLispCompilerIntegrationTest` + ci-spec `rontolisp-package-introspection`
  + `doc/{en,ja}/reference/packages.md` + `rontolisp-list-macros.md`; run the
  `-Drontolisp.doc.fix=true` DocExamplesTest helper.
- New operators need doc pages (en+ja) + `_catalog.yaml` entries + a row in the
  curated `doc/{en,ja}/reference/{functions,macros}.md` table.
- `./mvnw test` green; native `-Pnative` build + `CiSpecE2eTest
  -Drontolisp.binary=...`; `./mvnw -q compile javadoc:jar`; `-Pweb compile` if
  any web-reachable signature changed.
- Remove `src/test/resources/assoc-utils/HANDOFF-driver.lisp` before finishing
  (it's a handoff aid, not part of the vendored library).

## WHY THIS LIB (candidate landscape, rechecked 2026-07-06)
The truly-easy ASDF libs are already done (split-sequence, parse-number,
cl-utilities, cl-who). Every remaining candidate needs 3-4 net-new features:
- `cl-base64`: HARD -- symbol-name colon-strip (cross-cutting) + compiled string
  mutation + condition system. Aborted, see `.todo/85`.
- `parse-float`: depends on alexandria (heavy). Skip.
- `global-vars`: portable fallback needs `define-symbol-macro` (anaphora blocker,
  `.todo/34`) + `&whole` + symbol plist. Skip until symbol-macros land.
- `trivial-types`: multi-file module, not yet triaged.
- **`assoc-utils`: THIS -- most tractable, all additive.**
Memory: `asdf-library-candidates`.
