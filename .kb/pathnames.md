# Pathnames: a distinct VALUE carrying its namestring

**Invariant**: a pathname is an instance of the fixed `LispLayout.PATHNAME` layout (kind
`PATHNAME`, tag `%PATHNAME`, one slot = the namestring); a string is NOT one; the two
spellings are interchangeable as ARGUMENTS to every path-taking operator. `pathnamep` /
`(typep x 'pathname)` answer `T` exactly for the value; `namestring` unwraps it.

## Value model
- The layout is a CONSTANT seeded into `ClosRegistry.layoutsByTag` as a LAYOUT ONLY: no
  `typep` tag table, no `structure-object`/`standard-object` enumeration, no
  `%class-slot-defs`; `(typep #P"x" 'structure-object)` is `NIL`, as in SBCL.
  `LispLayout.SYNONYM_STREAM` is the second value built this way
  (`.kb/read-load-streams.md`) — copy this pattern. Tag is UPPER case.
- Printing is a per-kind arm in the three instance printers (`LispInstance.render`, JVM
  `_instToString`/`_instToDisplayString`, WASM `emitPrintInstance`), NOT a `print-object`
  method: `prin1` writes `#P` + escaped namestring, `princ` the bare namestring (CLHS
  22.1.3.11), and nested elements follow — `(princ (list #P"a b"))` is `(a b)`, which the
  print-object seam cannot give.
- `equal`/`equalp` compare the namestring; `(equal #P"a" "a")` is `NIL`. Compile-path
  runtime `typep` uses one `%typep-tag-table%` entry (`(PATHNAME) %PATHNAME`); `type-of`
  answers `PATHNAME` via a string-compare clause in the prelude defun.

## `#P"..."` and the instance gate
`Token.PathnameOpen` for `#P"`/`#p"`; `LispReader` builds the `LispInstance` directly
(self-evaluating like a folded `#S(...)`). `#PFOO` stays a symbol. Both EMITTED readers
have a `#P` arm (JVM `Object[]{layout, ns}` off the interned `_ly$` field; WASM
`struct.new` over the baked layout address). A runtime `read` can CONSTRUCT an instance, so
`constructsInstance` answers true for `read`/`read-from-string`/`load` heads and a
read-using program is instance-gated; gate off, `%obj-is` compiles to constant nil
(`pathnamep` stays correct) and the readers' `#P` arm signals. A `#P` literal is what
`mayCreateInstances` detects, so it flips the gate itself.

## Producers wrap, consumers coerce, internals stay strings
Prelude pattern (`LispPreludeLibrary`): every PUBLIC path function coerces through
`%path-ns` (LENIENT) or the strict `namestring`, computes on namestrings, wraps its answer
with `(pathname ...)`.
- String-typed internals: `%pathname-split`, `%dir-namestring`, `%wild-match`,
  `%wild-captures`, `%wild-component-p`, `%wild-inferiors-at`,
  `%pathname-directory-component`, `%path-dir-parts`, `%wild-dirs`, `%directory-subdirs`,
  `%directory-in`, `%temp-file-name`, `%probe-file`, `%list-directory`, `%delete-file`,
  `%make-directories`, `%rename-file`.
- Consumers unwrap: interpreter built-ins via `PathnameOps.designatorNamestring`; the
  compile paths wrap `open`/`load`/`file-write-date`'s path argument in
  `LispMacroExpander.coercePathArg`, ONLY when the instance gate is on, so an
  instance-free program keeps its bytes.
- Deliberately still namestrings: `asdf:system-source-directory` / `component-pathname` /
  `system-relative-pathname` answers (`.kb/asdf.md`), `ensure-directories-exist`'s value,
  `uiop:with-temporary-file`'s `:pathname` binding. `uiop:native-namestring` =
  `namestring`; uiop lowerings run AFTER the prelude splice, so
  `LispPreludeLibrary.referencedBySurfaceForm` splices `probe-file` for a program spelling
  only `uiop:file-exists-p`. `probe-file`'s `BuiltinFunctionWrappers` entry is GONE; the
  prelude defun serves `#'probe-file`.
- `CompileTimePathnameFolder` folds `make-pathname` / `uiop:merge-pathnames*` to a pathname
  literal and records them for the `defparameter` substitution; `LoadInliner` inlines
  `(load #P"x.lisp")`. A `typecase` `pathname` clause is an ordinary `%obj-is` test. The
  transport still REFUSES a pathname response body (`%http-body-string` /
  `LispEvaluator.responseBody`); `lack-app-file` is deferred.

## Wild components: `:wild` / `:wild-inferiors`
The flat model renders CL's directory keywords as the text CL prints: `:up`/`:back` ->
`..`, `:wild` -> `*`, `:wild-inferiors` -> `**` (`%pathname-directory-string`, Java twin
`PathnameOps.formatDirectory`); `:wild` is also `:name`/`:type`'s `*`.
- **The round trip is the namestring, and it is total** — there is no structured pathname
  at all. Decomposition is the exact INVERSE of construction; only an EXACT `*` is a
  keyword (`"a*"` stays a string, as SBCL).
- **`**/` is ONE matcher token, separator included** — `%wild-inferiors-at` is the single
  spelling, read by `%wild-match`, `%wild-captures` AND `translate-pathname`'s substitution
  scan, so the three cannot disagree. The trailing `/` belongs to the token because it
  matches ZERO levels as well as many; it contributes ONE capture (`""` when none).
- Building a wild pathname is filesystem-INDEPENDENT; only `directory`'s WALK touches
  `%list-directory` (`.kb/directory-listing.md`).

## Deliberate lite edges
`(eql #P"a" #P"a")` is T on the interpreter, NIL on the compile paths — the pre-existing
instance `eql` split; **do not pin `eql`**. No PATHNAME metaobject: `class-of` falls back,
`find-class 'pathname` is absent, `sxhash` is 0. Logical pathnames cannot exist:
`logical-pathname` always signals, `translate-logical-pathname` is the identity.
`pathname-host`/`-device`/`-version` answer `nil` (SBCL answers `:newest` for VERSION).

## The algebra over the flat namestring
All prelude Lisp, so the four backends run ONE definition each.
- `wild-pathname-p` reads the same `%pathname-split` and wildcards `%wild-match` uses, so
  predicate and matcher cannot disagree. `enough-namestring` is the INVERSE of
  `merge-pathnames` and answers a STRING. `host-namestring` is `""`, written
  `(progn (namestring x) "")` so the designator is still validated.
- `translate-pathname` — `%wild-captures`, the CAPTURING twin of `%wild-match` (`:no-match`
  is the failure answer; `*` is tried SHORTEST first), substituting left to right over the
  FLAT namestring, so a `*` may span `/`. **Trap: substitution is POSITIONAL, not
  component-wise** — SBCL answers `"x/b-y.c"` for `(translate-pathname "a/b.c" "*/*.*"
  "x/*-y.*")` where this answers `"x/a-y.b"`.
- `rename-file` — prelude Lisp over `%rename-file`: interpreter and JVM rename for real,
  both WASM backends lower to a call-time signal (`LispMacroExpander.renameFileStub`).
  Truename values 2 and 3 are not returned.
- `file-namestring` / `directory-namestring` — one split: `%pathname-split`'s first element
  is a literal PREFIX, so `file-namestring` is `(subseq ns (length it))`, exact
  complements. NOT built from name + `"."` + type: that loses a trailing dot and re-decides
  the dotfile rule.
- **`*default-pathname-defaults*` is a genuine dynamic variable on all four backends**,
  holding `#P""`: `Environment.createGlobal` (proclaimed special) / a `defvar` from
  `LispMacroExpander`'s `PRINTER_MODE_VARS` injection, which runs AFTER
  `mayCreateInstances`, so mentioning it flips the instance gate like a `#P` does.
  `uiop:get-pathname-defaults` reads this special (`.kb/uiop.md`).

## Tests
`LispReaderTest#readPathname*`; `LispEvaluatorTest#pathname*` plus its wild-pathname,
`enoughNamestring`, `namestringHalves`, `translatePathname`, `renameFile` and
`directoryFamilyAnswersPathnames` cases; `LispPreludeLibraryTest` (PathnameOps agreement
pins); `Jvm/WasmLispCompilerTest`'s `pathnameAlgebraOverTheFlatNamestring`,
`wildPathnameComponentsBuildMatchTranslateAndWalk`,
`namestringHalvesNstringCaseAndEnvironmentEnquiry` and twins; ci-spec
`pathname-algebra-over-the-flat-namestring`, `pathname-family-and-broadcast-streams`,
`lite-builtins-residue`, `probe-file-existing-and-missing`,
`directory-listing-and-uiop-walkers`,
`namestring-halves-nstring-case-and-environment-enquiry`, `wild-pathnames`.
