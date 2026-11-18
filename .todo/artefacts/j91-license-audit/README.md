# License and provenance audit (2026-11-18)

Read-only audit of develop `b61049e2d`. Working judgement, not legal advice. "Verified" = compared
against the upstream source or artefact; "Compared" = machine-compared with a local upstream copy
(Clojure 1.12.6 jar, JDK 25 src.zip, Quicklisp); "Guess" = upstream compared from memory.
"Embedded" = ends up in a user's compiled output (.class, .wasm, native executable).

Project-wide: there is no root `NOTICE` and `LICENSE` has no third-party section; rule 5 of
`.kb/java-frontend-provenance.md` presumes a `NOTICE`.

## Rewrite (j91: OpenJDK-derived, j92: EPL-derived)

| # | Where | Upstream (licence) | Evidence | Embedded | Basis |
|---|---|---|---|---|---|
| 1 | `src/main/java/am/ik/rontolisp/codegen/wasm/WasmFdlibmRuntimeBuilder.java` (lines 20-42, `Sources` ~1763-) | OpenJDK `java.lang.FdLibm` (GPLv2+CPE) | says "transliterated statement for statement from the JDK's own Java port of fdlibm"; JDK-only constant names (`EXP_SIGNIF_BITS` ...) 28 times; introduced in `42ccdd859` | yes (both wasm backends) | Verified |
| 2 | `src/main/java/am/ik/rontolisp/codegen/wasm/WasmFloat16Compiler.java` (25-59, 556-640); `.todo/artefacts/482-bfloat16-a-narrow-width-that-pays/Enc.java` | OpenJDK `Float.floatToFloat16` | "a literal port of java.lang.Float.floatToFloat16"; JDK variable name `doppel` | yes (Enc.java no) | Verified |
| 3 | `src/main/resources/am/ik/rontolisp/eval/clojure.lisp` 27620-27915 (pprint float kernel) | `clojure/pprint/cl_format.clj` (EPL-1.0) | line-by-line; `append-zero`, `prepend-zero`, `signed-len`, same conditions | yes | Verified |
| 4 | `src/main/resources/am/ik/rontolisp/clojure/lib/clojure/` `main`, `repl`, `stacktrace`, `data`, `core/reducers`, `zip` (`.clj`) | same Clojure namespaces (EPL-1.0) | bodies, branch order, a regex, docstring openings identical | yes (when required) | Verified (part) / Compared |
| 5 | `src/main/resources/am/ik/rontolisp/clojure/lib/rontolisp/internal/core_macros.clj` 793-1034 | `clojure/core_deftype.clj` (EPL) | `parse-opts`, `parse-impls`, `validate-fields` messages, defrecord method templates | yes (run-time macroexpand) | Compared |
| 6 | `clojure.lisp` 29440-29560 (`%clojure-big-integer-new`) | OpenJDK `BigInteger(String,int)` | `.kb` says "ported"; check order and `digitsPerInt` table | yes | Compared |
| 7 | `clojure.lisp` 13873-13945; `src/main/java/am/ik/rontolisp/clojure/ClojureFormatLowering.java` 394-490 | OpenJDK `Formatter` check methods | same forbidden-flag sets and order; Java side keeps `checkGeneral` etc. | Lisp side yes | Compared |
| 8 | `src/main/java/am/ik/rontolisp/clojure/ClojureDepsGraph.java` 400-615 | tools.deps `deps.clj` (EPL-1.0) | `include-coord?`, `update-excl` translated function by function | no (compiler only) | Compared |
| 9 | `examples/llm-from-scratch/` | elith-co-jp/book-llm-from-scratch (no licence) | README: rewritten "almost line by line" from the book's PyTorch code | no | Verified (j94: moved out to a project of its own, 2026-11-18) |

## Add notices (j93)

| # | Where | Upstream (licence) | Evidence | Embedded |
|---|---|---|---|---|
| 10 | `src/main/resources/am/ik/rontolisp/clojure/lib/ring/` | ring-core 1.15.5 / ring-codec (MIT) | near-verbatim docstrings, bodies, MIME table; no copyright line | yes |
| 11 | `src/main/resources/am/ik/rontolisp/clojure/lib/riddley/walk.clj` | riddley 0.2.2 (MIT) | `walk-exprs` branch order, `case-form` destructuring | yes |
| 12 | `src/main/resources/am/ik/rontolisp/eval/tiny-routes-lite-path-template.lisp` 150-215 | tiny-routes v0.1.1 (BSD-3) | "reproduced VERBATIM"; BSD-3 text and copyright missing (clause 1) | when used |
| 13 | `src/main/resources/am/ik/rontolisp/eval/asdf-search.lisp`, the 13 `uiop-*.lisp`, part of `asdf.lisp` | ASDF 3.3.1 / UIOP (MIT) | "upstream's bodies (ASDF 3.3.1)"; upstream comment `;skip rest of header` in `uiop-os.lisp` | names referenced |
| 14 | `src/main/resources/am/ik/rontolisp/eval/cffi-strings.lisp` 107-159, `cffi-rontolisp.lisp` 30-107 | cffi (MIT) | near-verbatim including `;;;#` headings | when cffi used |
| 15 | `src/main/java/am/ik/maven/BuildMerger.java`, `ModelValidator.java`, the ConflictResolver port (`.kb/maven-resolver.md`), `ModelBuilder`'s super POM, `src/main/java/am/ik/rontolisp/clojure/ClojureMavenVersions.java` | Maven 3.9 / Resolver 1.9 (Apache-2.0) | "ported" stated; Apache-2.0 sec. 4 change notice and NOTICE missing | no (compiler) |
| 16 | `rontolisp-native/` `rlrun`, `rlrun-net`, `librlprecomp` | wasmtime 49 + 143 crates (MIT/Apache, BSD-3, ISC, Unicode-3.0, Zlib, CDLA-Permissive-2.0) | `cargo tree`; binary distribution needs the notices | yes (every `--native` output; exec jar holds 3 platforms) |
| 17 | `pom.xml` jline 4.4.7 | BSD-3 (bundles jansi + 17 native libs) | shaded into exec jar / native image without licence text | no (CLI) |
| 18 | `examples/llm/llm.lisp`, `examples/ml/tiny-llm.lisp`, `examples/llm/stories260K.bin`, `examples/llm/tok512.bin` | llama2.c `run.c`, tinyllamas (MIT) | "ported whole", no copyright line | no |
| 19 | `src/test/resources/`: tiny-routes, jose, with-user-abort (BSD), prove (MIT), cl-colors (Boost), cl-ansi-text, cl-reexport, lisp-namespace, circular-streams, clsql (LLGPL), `testsupport/wasi-keyvalue/*.wit` (Apache-2.0 WITH LLVM-exception) | licence text missing, only `.asd` `:license` | no |

## To confirm

| # | Where | Question |
|---|---|---|
| 20 | `src/main/java/am/ik/rontolisp/codegen/wasm/SchubfachTables.java`, `WasmSchubfachRuntimeBuilder.java` | JDK `DoubleToDecimal` (GPL+CPE) or Giulietti's original? `rop` and names match the JDK; table computed here. Establish the origin; rewrite from the paper if JDK (j91). Embedded. |
| 21 | `src/main/resources/am/ik/rontolisp/clojure/lib/clojure/pprint.clj` (dispatch), `clojure.lisp` 26635-27150 (pretty writer) | paraphrase of Clojure pprint (EPL); rewrite with #3 (j92) |
| 22 | `src/main/resources/am/ik/rontolisp/eval/starlark.lisp` 3273-3390 | starlark-go `string_format` (BSD-3) "ported" per `.kb`: notice it, or correct the wording if only behaviour was matched (j93) |
| 23 | `src/main/java/am/ik/rontolisp/eval/QuantizedMatrices.java` 28-95, JVM quantized runtime builder | ggml `quantize_row_q8_0_ref` (MIT) "transcribed": one NOTICE line (j93) |
| 24 | `src/main/resources/am/ik/rontolisp/java/library/java/util/Random.java` 16-19, `Hashtable.java` 91 | `seedUniquifier` constants and capacity `max(2*size,11)` are not in the specification (j91) |
| 25 | `src/main/resources/am/ik/rontolisp/eval/clsql-closer-mop.asd`, `petalisp-native-backend-rontolisp.asd` | near-copies of LLGPL / AGPL-3.0 defsystems; generate the difference at load time instead (j93) |
| 26 | `src/main/resources/am/ik/rontolisp/eval/` `sockets`, `http`, `tls` ... `.wit` (`tls.wit` is wasmtime v47.0.2's) | WASI / wasmtime (Apache-2.0 WITH LLVM-exception): source noted, licence not (j93) |
| 27 | Unicode tables (`ClUnicodeTables`, `SchemeCharacters`, `ClojureCaseMapping`, `clojure-unicode.lisp`) | data obtained by running JDK APIs; add the Unicode-3.0 notice (j93) |
| 28 | `src/main/java/am/ik/rontolisp/eval/LispPreludeLibrary.java` 3205-3290 (`%utf-8-malformed-length`) | equivalent conditions to OpenJDK `String.decodeUTF8_UTF16`, restructured; low risk, re-derive from the Unicode standard (j91) |
| 29 | `src/main/resources/am/ik/rontolisp/clojure/lib/rontolisp/internal/cljs_core.clj`; `ClojureDepsEdn.java` `ROOT_TEXT` | ClojureScript / tools.deps (EPL): `caching-hash` similar (guess); ~20 lines of EDN verbatim (j92) |
| 30 | `web/coi-serviceworker.min.js` | MIT, one-line header, full text missing (j93) |
| 31 | `examples/browser/hiragana/weights.bin` | trained on Kuzushiji-49 (CC BY-SA 4.0); attribution present, whether SA reaches the weights is open (j93) |
| 32 | `.todo/artefacts/b16-shcloj4-inventory/probes/` | Programming Clojure 4th code snippets (165 lines); low risk (j93) |
| 33 | `src/main/java/am/ik/rontolisp/eval/Uax15Tables.java`, `*Forms.java` | a user's compiled Quicklisp library carries no upstream notice in the output; document the user's obligation (j93) |

## Clean (main)

CL run-time shims (closer-mop, gray-streams, bordeaux-threads, flexi-streams, json, http,
sockets, localtime, `inflate.lisp` from RFC 1951, objc, `macro/*.lisp`, `*-exports.txt`); no
SBCL/PCL/CLISP/ECL traces. The 173 Java library files (0 Javadoc sentence matches of 60+
chars; `doc/en` likewise). Scheme; Starlark's Java side; Murmur3; HAMT; `adapter.wasm`; gemm;
CLtL2 backquote (public domain). Bundled: 39 test libraries with LICENSE (alexandria,
cl-ppcre, ironclad, rove, trivia ...), starlark-conformance (Apache-2.0), BIZ UDPGothic (OFL),
BudouX data (Apache-2.0), deep-learning-from-scratch (MIT, attributed), jspecify. ansi-test
and SICP corpora are fetched, never committed.

## History

2,147 deleted files across 9,666 commits checked: no third-party code. Items 1-8 and the
regex (j90) stay in history after the rewrite; the user decided (2026-11-18) to keep history.
