# Pathnames: a distinct VALUE carrying its namestring

**Invariant**: a pathname is an instance of the fixed `LispLayout.PATHNAME` layout (kind
`PATHNAME`, tag `%PATHNAME`, one slot = the namestring); a string is NOT one; the two
spellings are interchangeable as ARGUMENTS to every path-taking operator. `pathnamep` /
`(typep x 'pathname)` answer `T` exactly for the value; `namestring` unwraps it.

## Value model

- The layout is a CONSTANT seeded into `ClosRegistry.layoutsByTag` in the constructor as a
  LAYOUT ONLY (like the `%UNBOUND%` marker): `%obj-new`/`%obj-is` resolve the tag on every
  backend (JVM `LayoutPool` interns on demand, WASM blob bakes it), but the type joins NO
  `typep` tag table, no `structure-object`/`standard-object` enumeration
  (`makeAnyStructInstanceTest` filters on kind `STRUCT`) and no `%class-slot-defs`.
  `(typep #P"x" 'structure-object)` is `NIL`, as in SBCL. `LispLayout.SYNONYM_STREAM` is
  the second value built this way (`.kb/read-load-streams.md`) -- copy this pattern. The
  tag is UPPER case so prelude Lisp can quote it under the reader-upcase premise.
- Printing is a per-kind arm in the three instance printers (`LispInstance.render`, JVM
  `_instToString`/`_instToDisplayString`, WASM `emitPrintInstance`), NOT a `print-object`
  method: `prin1` writes `#P` + escaped namestring, `princ` the bare namestring (CLHS
  22.1.3.11), and nested elements follow -- `(princ (list #P"a b"))` is `(a b)`, which the
  print-object seam cannot give.
- `equal`/`equalp` compare the namestring through the ordinary instance arms;
  `(equal #P"a" "a")` is `NIL`. Runtime `(typep x 'pathname)` on the compile paths uses one
  `%typep-tag-table%` entry (`(PATHNAME) %PATHNAME`). `type-of` answers `PATHNAME` via a
  string-compare clause in the prelude defun.

## `#P"..."` and the instance gate

`Token.PathnameOpen` for `#P"`/`#p"`; `LispReader` builds the `LispInstance` directly (the
layout is a constant, the value self-evaluating like a folded `#S(...)`). `#PFOO` stays a
symbol. The `#+`-skip's generic tail also skips a string directly after a `#`-prefix.

Both EMITTED readers have a `#P` arm (JVM builds `Object[]{layout, ns}` from the interned
`_ly$` field; WASM `struct.new`s over the baked layout address). Since a runtime `read`
can CONSTRUCT an instance, `constructsInstance` answers true for
`read`/`read-from-string`/`load` heads (and `#'read`/`#'read-from-string`) -- a read-using
program is instance-gated. Gate off: `%obj-is` compiles to constant nil (`pathnamep` stays
correct) and the readers' `#P` arm signals rather than answering a mistyped value. A `#P`
literal in the AST is what `mayCreateInstances` detects, so it flips the gate itself.

## Producers wrap, consumers coerce, internals stay strings

Prelude pattern (`LispPreludeLibrary`): every PUBLIC path function coerces through
`%path-ns` (LENIENT: pathname -> namestring, anything else unchanged, preserving
"non-string coerces to the empty namestring") or the strict `namestring`, computes on
namestrings, wraps its answer with `(pathname ...)`.

- String-typed internals on every backend: `%pathname-split`, `%dir-namestring`,
  `%wild-match`, `%wild-captures`, `%wild-component-p`, `%wild-inferiors-at`,
  `%pathname-directory-component`, `%path-dir-parts`, `%wild-dirs`, `%directory-subdirs`,
  `%directory-in`, `%temp-file-name`, `%probe-file`, `%list-directory`, `%delete-file`,
  `%make-directories`, `%rename-file`.
- Producers: `pathname`, `parse-namestring` (lite: value + length), `make-pathname`,
  `merge-pathnames`, `translate-pathname`, `translate-logical-pathname`, `probe-file`
  (prelude over `%probe-file`; its `BuiltinFunctionWrappers` entry is GONE, the defun
  serves `#'probe-file`), `truename`, `directory`, `uiop:directory-exists-p`,
  `uiop:ensure-directory-pathname`, `uiop:default-temporary-directory`,
  `uiop:file-exists-p`, `uiop:merge-pathnames*`.
- Consumers: `open`/`with-open-file`, `load`, `file-write-date`, `delete-file`,
  `rename-file` (also produces the defaulted new name), `ensure-directories-exist`,
  `uiop:delete-file-if-exists`, `asdf:system-relative-pathname`'s 2nd argument.
  Interpreter built-ins unwrap via `PathnameOps.designatorNamestring`; the compile paths
  wrap `open`/`load`/`file-write-date`'s path argument in
  `LispMacroExpander.coercePathArg` (a `let` + `%obj-is`/`%obj-ref` unwrap, primitives
  only), ONLY when the instance gate is on, so an instance-free program keeps its bytes.
- Deliberately still namestrings: `asdf:system-source-directory` / `component-pathname` /
  `system-relative-pathname` answers (`.kb/asdf.md`), `ensure-directories-exist`'s value
  (CL returns the ARGUMENT pathspec), `uiop:with-temporary-file`'s `:pathname` binding.
- `uiop:native-namestring` = `namestring`. uiop lowerings run inside the expression
  compilers, AFTER the prelude splice, so `LispPreludeLibrary.referencedBySurfaceForm`
  splices `probe-file` for a program spelling only `uiop:file-exists-p`, and `namestring`
  for `uiop:namestring`/`uiop:native-namestring`.
- `CompileTimePathnameFolder` folds `make-pathname` / `uiop:merge-pathnames*` to a
  `LispInstance` pathname literal, accepts `#P` literals and folded pathnames as arguments
  (`PathnameOps.designatorNamestring`), records them for the `defparameter` substitution;
  the `with-open-file` content-bundling rewrite matches them as paths. `LoadInliner`
  inlines `(load #P"x.lisp")`.
- A `typecase`/`etypecase` `pathname` clause is an ordinary `%obj-is` test
  (`LispMacroExpander.pathnameClauseYields` + `matchesAString` are DELETED). The transport
  still REFUSES a pathname response body (`%http-body-string` /
  `LispEvaluator.responseBody` fall to the unsupported-type arm) -- serving a
  `lack-app-file` body is deferred.

## Wild components: `:wild` / `:wild-inferiors`

The flat model renders CL's directory keywords as the namestring text CL prints:
`:up`/`:back` -> `..`, `:wild` -> `*`, `:wild-inferiors` -> `**`
(`%pathname-directory-string`, Java twin `PathnameOps.formatDirectory`). `:wild` is also
`:name`/`:type`'s `*` (`%pathname-component-string`).

**The round trip is the namestring, and it is total** -- `#P"/a/**/*.lisp"` reads back;
there is no structured pathname at all. Decomposition is the exact INVERSE of
construction: `pathname-directory` maps `..`/`*`/`**` back to
`:up`/`:wild`/`:wild-inferiors` (`%pathname-directory-component`);
`pathname-name`/`pathname-type` answer `:wild` for a component that is exactly `*`. Only
an EXACT match is a keyword (`"a*"` stays a string, as SBCL). `%pathname-split` stays
string-typed: `directory`'s matcher and `make-pathname`'s `:defaults` read it.

**`**/` is ONE matcher token, separator included.** `%wild-inferiors-at` is the single
spelling of "a wild-inferiors segment starts here", read by `%wild-match`,
`%wild-captures` AND `translate-pathname`'s substitution scan, so the three cannot
disagree. The trailing `/` is part of the token because it matches ZERO levels as well as
many -- only swallowing the separator lets `"/a/**/*.lisp"` match `"/a/c.lisp"`. It
contributes ONE capture (the whole run, `""` when none), written back verbatim by a `**/`
in the to-wildcard.

Building a wild pathname is filesystem-INDEPENDENT (browser playground, `--no-wasi`);
only `directory`'s WALK touches `%list-directory` (`.kb/directory-listing.md`).

## Deliberate lite edges

- `(eql #P"a" #P"a")` is T on the interpreter (structural `LispInstance.equals`), NIL on
  the compile paths (reference compare) -- the pre-existing instance `eql` split. **Do not
  pin `eql`.**
- No PATHNAME metaobject: `class-of` gives the interpreter's `T` built-in-class fallback
  and a catchable error on the compile paths; `find-class 'pathname` is absent; `sxhash`
  is the instance fallback 0.
- Logical pathnames cannot exist (no logical HOST, no `logical-pathname-translations`):
  `logical-pathname` always signals, `translate-logical-pathname` is the identity (CL's
  own answer for a physical argument). Re-evaluate only if a translation table is added.
- `pathname-host`/`-device`/`-version` answer `nil`; SBCL answers `:newest` for VERSION.

## The algebra over the flat namestring

All prelude Lisp, so the four backends run ONE definition each.

- `wild-pathname-p` -- `%wild-component-p` (holds `*` or `?`) on the component the
  optional field key names, or all. Reads the SAME `%pathname-split` and wildcards
  `%wild-match` uses, so predicate and matcher cannot disagree.
- `enough-namestring` -- INVERSE of `merge-pathnames`: the namestring with the defaults'
  directory prefix removed, or whole when it does not start with it. Value is a STRING.
- `translate-pathname` -- `%wild-captures`, the CAPTURING twin of `%wild-match`
  (`:no-match` is the failure answer no capture list can collide with; `*` is tried
  SHORTEST first so `"*/*.*"` splits at the first `/`), then substitution into the
  to-wildcard left to right. Matching runs over the FLAT namestring, so a `*` may span `/`.
  **Trap: substitution is POSITIONAL, not component-wise.** SBCL answers `"x/b-y.c"` for
  `(translate-pathname "a/b.c" "*/*.*" "x/*-y.*")` where this answers `"x/a-y.b"`.
  Survivable because real libraries write the same wildcard sequence on both sides.
- `rename-file` -- prelude Lisp over `%rename-file`, third write-side sibling of
  `%list-directory`/`%make-directories`/`%delete-file`: interpreter (`Files.move`) and JVM
  (`_renameFile`, `File.renameTo`) rename for real; both WASM backends lower to a
  call-time signal (`LispMacroExpander.renameFileStub`, the `deleteFileStub` rule). CL's
  truename values 2 and 3 are not returned.
- `file-namestring` / `directory-namestring` -- one split, not two computations:
  `%pathname-split`'s first element is a literal PREFIX, so `directory-namestring` IS that
  element and `file-namestring` is `(subseq ns (length it))` -- exact complements, and
  they cannot drift from `pathname-name`/`pathname-type`. SBCL-checked: `"/a/b/c.txt"` ->
  `("c.txt" "/a/b/")`, `"a.txt"` -> `("a.txt" "")`, `"/a/b/"` -> `("" "/a/b/")`,
  `"/a/.bashrc"` -> `(".bashrc" "/a/")`. NOT built from name + `"."` + type: that loses a
  trailing dot and re-decides the dotfile rule.
- `host-namestring` -- `""`, written `(progn (namestring x) "")` so the designator is
  still validated. `pathname-host`/`-device`/`-version` validate the same way.

**`*default-pathname-defaults*` is a genuine dynamic variable on all four backends**,
holding `#P""`. The interpreter defines it in `Environment.createGlobal` and proclaims it
special; the compile paths get a `defvar` from `LispMacroExpander`'s `PRINTER_MODE_VARS`
injection for a program that MENTIONS it. That injection runs AFTER `mayCreateInstances`
and its value is an instance, so `mayCreateInstance` answers for the variable's NAME --
mentioning it flips the instance gate like a `#P` does. `uiop:get-pathname-defaults` reads
this special; the `uiop/pathname` algebra is written over this flat model (`.kb/uiop.md`).

## Tests

- `LispReaderTest#readPathname*`
- `LispEvaluatorTest#pathname*`, `#pathnameComponentsRontolispDoesNotModelAnswerNil`,
  `#wildPathnamePAnswersPerComponent`, `#enoughNamestringDropsTheDefaultsDirectoryPrefix`,
  `#namestringHalvesSplitAtTheDirectoryBoundary`,
  `#translatePathnameSubstitutesTheCapturedWildcards`,
  `#renameFileMovesTheFileAndSignalsWhenItIsNotThere`, `#directoryFamilyAnswersPathnames`,
  `#pathnameDiscriminatesFromStringContentInLackAndJzonShapes`
- `LispPreludeLibraryTest` (PathnameOps agreement pins)
- `Jvm/WasmLispCompilerTest` probe-file/directory/lite-builtins;
  `JvmLispCompilerTest#pathnameAlgebraOverTheFlatNamestring`, `#renameFileMovesTheFileOnDisk`;
  `WasmLispCompilerIntegrationTest#pathnameAlgebraOverTheFlatNamestring`,
  `#componentPathnameAlgebraOverTheFlatNamestring`
- `LispEvaluatorTest`/`JvmLispCompilerTest#wildPathnameComponentsBuildMatchTranslateAndWalk`,
  `WasmLispCompilerIntegrationTest#wildDirectoryComponentsDriveTheRecursiveWalk` + twin
- `Jvm/WasmLispCompilerTest#namestringHalvesNstringCaseAndEnvironmentEnquiry` + twin
- ci-spec: `pathname-algebra-over-the-flat-namestring`,
  `pathname-family-and-broadcast-streams`, `lite-builtins-residue`,
  `probe-file-existing-and-missing`, `directory-listing-and-uiop-walkers`,
  `namestring-halves-nstring-case-and-environment-enquiry`, `wild-pathnames`
