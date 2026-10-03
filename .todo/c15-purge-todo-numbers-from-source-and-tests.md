# c15. Purge todo numbers from source and tests

Difficulty: Low

Todo numbers must not appear in source code, comments, messages, test names or test data.
They have leaked in anyway. Count of `(bNN)`/`(cNN)` comments and `bNN-`/`cNN-` identifier
prefixes under `src/` (2026-10-03, `6d7783303`):

- `src/test/resources/clojure-spec.yaml`: 856 (case names like `c12-comparison-...`, identifiers like `b93-chain`, `b17-fact`, `# read-string (b85)` comments)
- `ClojureInteropTest` 53, `ClojureLoweringTest` 17, `clojure.lisp` 9 (`;;;; Vars: #'x as a value (b80).`), `LispEvaluatorTest` 7, `WasmLispCompilerIntegrationTest` 6, `JvmLispCompilerTest` 6, and a few more in `src/main/java` (`ClojureMacroLowering`, `ClojureInteropLowering`, `WasmSetqCompiler`, `JvmSocketRuntimeBuilder`).

Also check other `*-spec.yaml`, `examples/`, `doc/` and `docs-tool/` with the same patterns.
Watch out for false positives such as `c00`..`c22` matrix variables in `geom.lisp`, hex digests and commit IDs.

Rename identifiers to what they mean (`b93-chain` -> `chain`, keeping names unique within each spec case), and rewrite comments without the number.
Spec case names describe behavior. Program output must not change: run `ClojureSpecE2eTest` and every touched test class.

`.kb/` and `.todo/` may cite todo numbers; leave them.
