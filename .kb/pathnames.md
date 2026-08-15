# Pathnames: a distinct VALUE carrying its namestring

**The invariant**: a pathname is an instance of the fixed `LispLayout.PATHNAME`
layout (kind `PATHNAME`, tag `%PATHNAME`, one slot holding the namestring), a
string is NOT one, and the two spellings are interchangeable as ARGUMENTS to
every path-taking operator. `pathnamep` / `(typep x 'pathname)` answer `T`
exactly for the value; the producers answer it; `namestring` unwraps it. This
restored CL's rule (todo-304): before it, `pathnamep` was `stringp`, and lack's
`finalize-response` — a `cond`, out of reach of the typecase yield heuristic —
shaped every ningle string body as a bare-string response the transport
refuses.

## The value model

The layout is a CONSTANT (`LispLayout.PATHNAME`), seeded into
`ClosRegistry.layoutsByTag` in the constructor as a LAYOUT ONLY — exactly like
the `%UNBOUND%` marker, and for the same reason: `%obj-new`/`%obj-is` resolve
the tag on every backend (the JVM `LayoutPool` interns it on demand, the WASM
blob bakes it with every other layout) while the type joins **no** `typep` tag
table, no `structure-object`/`standard-object` enumeration
(`makeAnyStructInstanceTest` filters on kind `STRUCT`; pathname is kind
`PATHNAME`) and no `%class-slot-defs` answer. `(typep #P"x"
'structure-object)` is `NIL`, as in SBCL. `LispLayout.SYNONYM_STREAM` is the second value
built this way (`.kb/read-load-streams.md`), so the pattern below is the shape to copy, not
a one-off. The tag is spelled `%PATHNAME` in
UPPER case so prelude Lisp can quote it literally under the reader-upcase
premise.

Printing is a per-kind arm in the three instance printers
(`LispInstance.render`, the JVM `_instToString`/`_instToDisplayString` pair,
WASM `emitPrintInstance`), NOT a `print-object` method: `prin1` writes `#P` +
the escaped namestring, `princ` the bare namestring (CLHS 22.1.3.11), and
nested elements follow — `(princ (list #P"a b"))` is `(a b)` — which the
print-object seam's handed-value-only limitation could not give. `equal` and
`equalp` compare the namestring through the ordinary instance arms (layout
identity/tag + slot-wise); `(equal #P"a" "a")` is `NIL`. Runtime `(typep x
'pathname)` on the compile paths answers through one extra
`%typep-tag-table%` entry (`(PATHNAME) %PATHNAME`); a non-instance needs no
case there. `type-of` answers `PATHNAME` via a string-compare clause in the
prelude defun.

## `#P"..."`, and the reader/gate coupling

The lexer emits `Token.PathnameOpen` for `#P"`/`#p"` and `LispReader` builds
the `LispInstance` directly — the layout is a constant, so no registry is in
scope, and the value is self-evaluating like a folded `#S(...)`. `#PFOO` stays
a symbol. The `#+`-skip's generic tail also skips a string directly following
a `#`-prefix, or the namestring would surface as a datum of its own.

Both EMITTED readers grew a `#P` arm (frontend parity, todo-172): the JVM
builds `Object[]{layout, ns}` from the interned `_ly$` field, WASM
`struct.new`s over the baked layout address after reading the string through
the ordinary recursion. Because a runtime `read` can therefore CONSTRUCT an
instance, `constructsInstance` answers true for `read` / `read-from-string` /
`load` heads (and `#'read`/`#'read-from-string`) — a read-using program is
instance-gated. With the gate off no pathname can exist, so `%obj-is` compiles
to constant nil (`pathnamep` stays correct) and the readers' `#P` arm signals
instead of answering a mistyped value.

## Producers wrap, consumers coerce, internals stay strings

The prelude pattern (`LispPreludeLibrary`): every PUBLIC path function coerces
its argument through `%path-ns` (the LENIENT unwrap: pathname → namestring,
anything else unchanged, preserving the "non-string coerces to the empty
namestring" tolerance) or the strict `namestring`, computes on namestrings,
and wraps its answer with `(pathname ...)`. Internals (`%pathname-split`,
`%dir-namestring`, `%wild-match`, `%wild-captures`, `%wild-component-p`,
`%temp-file-name`, the `%probe-file` / `%list-directory` / `%delete-file` /
`%make-directories` / `%rename-file` primitives) stay string-typed on every
backend.

- Producers: `pathname`, `parse-namestring` (lite: value + length), `make-pathname`,
  `merge-pathnames`, `translate-pathname`, `translate-logical-pathname`,
  `probe-file` (now prelude over the renamed `%probe-file`
  primitive; its `BuiltinFunctionWrappers` entry is GONE — the defun serves
  `#'probe-file`), `truename`, `directory` and the `uiop:` walkers,
  `uiop:directory-exists-p`, `uiop:ensure-directory-pathname`,
  `uiop:default-temporary-directory`, `uiop:file-exists-p`,
  `uiop:merge-pathnames*` (interpreter Java + the compile-time fold agree).
- Consumers: `open`/`with-open-file`, `load`, `file-write-date`, `delete-file`,
  `rename-file` (which also PRODUCES: the defaulted new name),
  `ensure-directories-exist`, `uiop:delete-file-if-exists`,
  `asdf:system-relative-pathname`'s second argument. On the interpreter the
  Java built-ins unwrap via `PathnameOps.designatorNamestring`; on the compile
  paths `open`/`load`/`file-write-date` get their path argument wrapped in
  `LispMacroExpander.coercePathArg` (a `let` + `%obj-is`/`%obj-ref` unwrap,
  primitives only, no splice) — applied ONLY when the instance gate is on, so
  an instance-free program keeps its exact bytes.
- Deliberately still namestrings: `asdf:system-source-directory` /
  `component-pathname` / `system-relative-pathname` answers (compile-time
  locator facts, identical on the interpreter; `find-system` itself answers
  the component METAOBJECT since todo-374, whose pathname readers stay
  namestrings — `.kb/asdf.md`),
  `ensure-directories-exist`'s value (CL returns the ARGUMENT pathspec),
  `uiop:with-temporary-file`'s `:pathname` binding (`%temp-file-name` is a
  namestring; its consumers accept both).

`uiop:native-namestring` became REAL in the same pass (= `namestring`; jzon's
pathname stringify method and trivial-mimes' `mime-probe` call it). The uiop
lowerings run inside the expression compilers — after the prelude splice — so
`LispPreludeLibrary.referencedBySurfaceForm` splices `probe-file` for a
program spelling only `uiop:file-exists-p`, and `namestring` for
`uiop:namestring`/`uiop:native-namestring`.

## The compile-time folder folds to pathname LITERALS

`CompileTimePathnameFolder` reduces `make-pathname` / `uiop:merge-pathnames*`
to a `LispInstance` pathname literal (so `(pathnamep (make-pathname ...))` is
T folded or not), accepts `#P` literals and folded pathnames as arguments
(`PathnameOps.designatorNamestring`), records them for the `defparameter`
substitution, and the `with-open-file` content-bundling rewrite matches them
as paths. `LoadInliner` inlines `(load #P"x.lisp")`. A pathname literal in the
AST is what `mayCreateInstances` already detects, so a `#P` in source flips
the gate by itself.

## What the fix retired, and what it must keep working

- `LispMacroExpander.pathnameClauseYields` + `matchesAString` are DELETED: a
  `typecase`/`etypecase` `pathname` clause is an ordinary `%obj-is` test now.
  jzon's `(typecase in (pathname (open in ...)) (t ...))`, its `(pathnamep
  stream)` cond and its `(value pathname)` method specializer all discriminate
  by the type itself.
- mito's migration path: `(check-type directory pathname)` passes because the
  caller hands `#P"db/"` (the guides' spelling) and `uiop:directory-files` /
  `merge-pathnames` / `make-pathname :defaults` answer pathnames end to end;
  `(etypecase file (null ...) (pathname ...))` takes the pathname branch.
  `uiop:file-exists-p` and `delete-file` accept the instances it hands them.
- lack's `finalize-response`: a STRING body takes the `(list (list body))`
  wrap branch (the todo-304 shape table, SBCL-identical), a pathname body
  passes through as the third element. The transport still refuses a pathname
  body (`%http-body-string` / `LispEvaluator.responseBody` fall to the
  unsupported-type arm) — serving a `lack-app-file` body is the deferred half.
- `.todo/222` folded in: `make-pathname` has a runtime form AND a distinct
  value on all four backends.

## Known lite edges (deliberate)

- `(eql #P"a" #P"a")` is T on the interpreter (structural `LispInstance.equals`)
  and NIL on the compile paths (reference compare) — the pre-existing instance
  `eql` split; SBCL interns pathnames and answers T. Do not pin `eql`.
- `class-of` on a pathname has no PATHNAME metaobject: the interpreter answers
  the `T` built-in-class fallback, the compile paths signal a catchable error
  (the pre-existing unregistered-tag behavior); `find-class 'pathname` is
  likewise absent. `sxhash` of a pathname is the instance fallback 0.
- Logical pathnames do not exist and cannot: there is no logical HOST and no
  `logical-pathname-translations` table, so `logical-pathname` always signals
  (CL requires a type-error unless the argument names a logical pathname, and
  nothing here can) and `translate-logical-pathname` is the identity -- which is
  CL's own answer for a physical argument. The pair is deliberate: answering a
  physical pathname from `logical-pathname` would claim a translation table
  exists. Re-evaluate only if a host/translation table is ever added.
- `pathname-host` / `-device` / `-version` answer `nil` -- "the component is not
  present", the answer CL prescribes for one that is not there, and SBCL's own
  answer on Unix for `:device`. SBCL answers `:newest` for the VERSION of a
  parsed namestring; `nil` is the one answer true of every pathname here.

## The algebra over the flat namestring (`.todo/036` closed here)

The last of the CL pathname surface landed 2026-08-15, all of it prelude Lisp
over the namestring, so the four backends run ONE definition each and cannot
drift:

- `pathname-host` / `pathname-device` / `pathname-version` -- `nil` (above),
  after validating the designator through the strict `namestring`.
- `wild-pathname-p` -- `%wild-component-p` (holds a `*` or a `?`) applied to the
  component the optional field key names, or to all of them. It reads the SAME
  `%pathname-split` the rest of the family does and the same two wildcards
  `%wild-match` matches with, so the predicate and `directory`'s matcher cannot
  disagree.
- `enough-namestring` -- the INVERSE of `merge-pathnames`: the merge prefixes a
  relative namestring with the defaults' directory, so the shortest namestring
  is the path with that prefix removed, and the whole namestring when it does
  not start with it. The value is a STRING, as CL specifies.
- `translate-pathname` -- `%wild-captures`, the CAPTURING twin of `%wild-match`
  (one matcher rule, two answers; `:no-match` is the failure answer no capture
  list can collide with, and `*` is tried SHORTEST first so `"*/*.*"` splits at
  the first `/`), then substitution into the to-wildcard left to right. Matching
  runs over the FLAT namestring, so a `*` may span a `/`.
- `rename-file` -- prelude Lisp over the new `%rename-file` primitive, the third
  write-side sibling of `%list-directory` / `%make-directories` /
  `%delete-file`: interpreter (`Files.move`) and JVM (`_renameFile`, a
  `File.renameTo`) rename for real, both WASM backends lower it to a call-time
  signal (`LispMacroExpander.renameFileStub`, the `deleteFileStub` rule --
  `.todo/257` owns closing that). CL's second and third values (the truenames)
  are not returned, the `ensure-directories-exist` rule.

**`*default-pathname-defaults*` is a genuine dynamic variable on all four
backends**, holding `#P""` -- the empty pathname, SBCL's own initial value and
the only honest one here, since rontolisp absolutizes nothing and names no
working directory (`.todo/356` owns the working directory). The interpreter
defines it in `Environment.createGlobal` and proclaims it special; the compile
paths get a `defvar` from `LispMacroExpander`'s `PRINTER_MODE_VARS` injection
for a program that MENTIONS it. That injection runs AFTER `mayCreateInstances`,
and its value is an instance, so `mayCreateInstance` answers for the variable's
NAME directly -- mentioning the variable flips the instance gate exactly the way
a `#P` in source does. `uiop:get-pathname-defaults` reads this special since
todo-357 retired the literal-`""` built-in; the whole `uiop/pathname` algebra
(50/50) is written over this flat model -- its decisions live in `.kb/uiop.md`.

## Pinning

`LispReaderTest#readPathname*`, `LispEvaluatorTest#pathname*` /
`#pathnameComponentsRontolispDoesNotModelAnswerNil` /
`#wildPathnamePAnswersPerComponent` /
`#enoughNamestringDropsTheDefaultsDirectoryPrefix` /
`#translatePathnameSubstitutesTheCapturedWildcards` /
`#renameFileMovesTheFileAndSignalsWhenItIsNotThere` /
`#directoryFamilyAnswersPathnames` /
`#pathnameDiscriminatesFromStringContentInLackAndJzonShapes` (the lack cond +
jzon typecase + mito check-type shapes), `LispPreludeLibraryTest` (the
PathnameOps agreement pins, now through `#P`),
`Jvm/WasmLispCompilerTest` probe-file/directory/lite-builtins tests,
`JvmLispCompilerTest#pathnameAlgebraOverTheFlatNamestring` /
`#renameFileMovesTheFileOnDisk`,
`WasmLispCompilerIntegrationTest#pathnameAlgebraOverTheFlatNamestring` /
`#componentPathnameAlgebraOverTheFlatNamestring`, and the
ci-spec cases `pathname-algebra-over-the-flat-namestring`,
`pathname-family-and-broadcast-streams` (predicates, printer,
`parse-namestring`, `merge-pathnames`, the lack `finalize-response` body cond
verbatim), `lite-builtins-residue`, `probe-file-existing-and-missing`,
`directory-listing-and-uiop-walkers` (all four backends).
