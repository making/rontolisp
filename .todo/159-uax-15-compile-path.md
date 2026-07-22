# uax-15 v0.1.3 on the JVM + WASM compile paths

Parent: `.todo/154` (cl-postgres dependency-library grind; uax-15 interpreter
gate closed there). Interpreter is fully green (`Uax15E2eTest#loadsAndRunsOnTheInterpreter`),
the four pathname / ASDF primitives are runtime functions in
`eval/PathnameOps` + `eval/Environment` + `eval/LispEvaluator`, and the LOOP
macro was reworked to per-clause iteration heads so both compose-hangul and
parse-integers work. What remains is teaching the compile path to lower those
four calls, and to bundle the `unicode-15-data/*.txt` for WASM. This todo is
self-contained so it can be picked up cold.

Goal state: `Uax15E2eTest#compilesAndRunsOnJvm`,
`Uax15E2eTest#compilesAndRunsOnWasmPreview1` and
`Uax15E2eTest#compilesAndRunsOnWasmComponent` all enabled and green; the three
`@Disabled` overrides in `src/test/java/am/ik/rontolisp/e2e/Uax15E2eTest.java`
deleted; the "compile paths (JVM + both WASM backends) are excluded" paragraph
in the class Javadoc removed. On the way `AsdfLibraryE2eSupport` should stop
needing per-library disables for this shape (once the mechanism is generic).

## Current failure surface

Running `./mvnw test -Dtest=Uax15E2eTest#compilesAndRunsOnJvm` (with the JVM
`@Disabled` removed) fails at compile time with:

```
UnsupportedOperationException: Cannot compile: MAKE-PATHNAME
```

thrown by `JvmFunctionCallCompiler.compileDirectCall` (see
`src/main/java/am/ik/rontolisp/codegen/jvm/JvmFunctionCallCompiler.java`
around the "Cannot compile: " + name" throw). The other three primitives
(`asdf:find-system`, `asdf:system-source-directory`, `uiop:merge-pathnames*`)
would fail on the same fallthrough once make-pathname is fixed --
`uiop:merge-pathnames*` currently expands via `expandUiopStubCall` into a
runtime "The function UIOP:MERGE-PATHNAMES* is undefined" error stub, which
compiles but blows up at run time when the load-time
`(defparameter *data-directory* ...)` fires.

Their call shapes in uax-15 are all in one place --
`src/test/resources/uax-15/src/precomputed-tables.lisp` line 5-7 and line 13
and line 52 -- and then `src/test/resources/uax-15/src/uax-15.lisp` line 53:

```lisp
;; precomputed-tables.lisp
(defparameter *data-directory*
  (uiop:merge-pathnames*
    (make-pathname :directory (list :relative "unicode-15-data")
                   :name nil :type nil)
    (asdf:system-source-directory (asdf:find-system 'uax-15 nil))))

(defvar *unicode-data*
  (with-open-file (in (uiop:merge-pathnames* *data-directory* "UnicodeData.txt")
                      :external-format :UTF-8)
    (loop for line = (read-line in nil nil) while line
       collect (cl-ppcre:split ";" line))))

(with-open-file (in (uiop:merge-pathnames* *data-directory* "CompositionExclusions.txt")
                    :external-format :UTF-8)
  ...)

;; uax-15.lisp
(defparameter *derived-normalization-props-data-file*
  (uiop:merge-pathnames* *data-directory* "DerivedNormalizationProps.txt"))
```

Three of the calls have both args literal at compile time. Only the second
and third form the `<*data-directory* + filename>` combinations, and one of
the `*data-directory* + filename` cases is a `defparameter` init (the last
one).

## Two viable approaches

**A. Compile-time substitution in `LoadInliner`** (**recommended, cross-
backend**). When `spliceSystem` inlines a system's forms, walk them and
substitute the specific patterns:

- `(asdf:find-system 'NAME [nil])` where NAME is a literal designator known to
  the loaded systems -> literal `"NAME"` string.
- `(asdf:system-source-directory X)` where X reduces to a known system name
  literal -> the baseDir string with a trailing `/`.
- `(make-pathname :directory (list :relative "STR") :name nil :type nil)` and
  `(make-pathname :directory '(:absolute "a" "b") ...)` -> the composed
  namestring literal (share `eval/PathnameOps.makePathname`).
- `(uiop:merge-pathnames* A B)` where both A and B are literal strings -> a
  literal string via `eval/PathnameOps.mergePathnames`.
- `(uiop:merge-pathnames* VAR "file.txt")` where VAR is a `defparameter` init
  we already resolved to a literal string -> lower to
  `(concatenate 'string VAR "file.txt")` (or a smarter form if VAR is known to
  end in `/`). Do NOT substitute when VAR could vary at run time.

Emit warnings for any residual call the compiled binary would still hit, so
we don't silently regress. Add a `CompileTimePathnameFolder` (or similar) as
its own class next to `WitExportInliner` / `WitImportInliner` for symmetry.

Coverage: extend `LoadInlinerTest` (`src/test/java/am/ik/rontolisp/cli/`) with
a per-pattern test, plus one end-to-end test that inlines a tiny system that
uses each of the four primitives.

**B. Runtime function support on JVM + WASM**. Add per-backend compilers for
each of the four functions -- `JvmMakePathnameCompiler`, etc. -- that emit
bytecode calling into the same `PathnameOps` methods. This keeps the compiled
binary's behavior identical to the interpreter for arbitrary (non-literal)
call sites, but is more work and doesn't help WASM which still can't access
the host filesystem.

Recommend A alone if the substitution passes are complete (all uax-15 uses
reduce to literals); fall back to A+B if any residue would trap a compiled
program.

## WASM: bundling `unicode-15-data/*.txt`

Even after the compile-time substitutions, WASM can't `with-open-file` the
data files because the wasmtime sandbox has no `--dir` mounted (see
`AsdfLibraryE2eSupport.runWasm`). The clean answer is to bundle the files as
string literals: at `LoadInliner` splice time, detect
`(with-open-file (in <literal-path> :external-format :UTF-8) BODY)`
where the literal path lives inside a spliced system's tree and the file is
UTF-8 text -- and substitute with
`(with-input-from-string (in "<file contents>") BODY)`. Guard on file size
(the UTF-8 UnicodeData.txt is ~1.7 MB) and back off with a warning if a
per-backend baked-constant limit would blow -- see `.todo/17`
(`jvm-baked-constant-limit`) for the JVM ceiling.

Alternative for WASM: mount `--dir` in `AsdfLibraryE2eSupport.runWasm` and
copy the data files into the container. Simpler for tests but leaves the
compiled binary depending on run-time filesystem access -- not useful for
users. Prefer bundling.

## Verification chain

Every change re-runs:

1. `./mvnw test -Dtest=Uax15E2eTest` -- all four backends green.
2. `./mvnw test -Dtest=ClPpcreE2eTest` -- LOOP macro regression guard, must
   still be green (the interpreter session verified this).
3. `./mvnw test -Dtest=ParseNumberE2eTest` -- also LOOP regression guard.
4. `./mvnw test` -- full suite (~10 min on this laptop).
5. Manual four-backend spot-check per `CLAUDE.md` -- interpreter, JVM,
   WASM Preview 1, WASM Component -- on a tiny `(uax-15:normalize ...)`
   script under `--system-path` to the vendored `src/test/resources/uax-15`.
6. Native E2E per `CLAUDE.md` if the `ci-spec.yaml` corpus changed.

## Files to touch (starting point)

- `src/main/java/am/ik/rontolisp/cli/LoadInliner.java` -- the walker /
  substitution pass belongs here, next to the existing `spliceSystem`.
- New `src/main/java/am/ik/rontolisp/cli/CompileTimePathnameFolder.java`
  (or similar) -- the pass itself.
- `src/main/java/am/ik/rontolisp/eval/PathnameOps.java` -- already has
  `makePathname` and `mergePathnames`; reuse them.
- `src/test/java/am/ik/rontolisp/cli/LoadInlinerTest.java` -- new tests.
- `src/test/java/am/ik/rontolisp/e2e/Uax15E2eTest.java` -- delete the three
  `@Disabled` overrides and update the class Javadoc.
- `.todo/154-cl-postgres-dependency-library-grind.md` -- flip uax-15's status
  from "INTERPRETER DONE" to "ALL FOUR BACKENDS" and remove the compile-path
  follow-up paragraph.
- `.kb/asdf.md` -- add a paragraph on compile-time pathname folding.
- `doc/{en,ja}/guides/asdf-systems.md` -- update if user-visible surface
  changed.
- `.todo/.history.md` -- record when this todo closes (per `CLAUDE.md`).

## Non-goals

- General runtime pathname support (`pathname` values distinct from strings).
- General filesystem access from the WASM sandbox.
- The `md5` WASM exclusion (that's `.todo/154`'s bignum idea; separate).
- The `ironclad` real-source loading (also separate under `.todo/154`).
