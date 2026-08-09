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
'structure-object)` is `NIL`, as in SBCL. The tag is spelled `%PATHNAME` in
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
`%dir-namestring`, `%wild-match`, `%temp-file-name`, the `%probe-file` /
`%list-directory` / `%delete-file` / `%make-directories` primitives) stay
string-typed on every backend.

- Producers: `pathname`, `parse-namestring` (lite: value + length), `make-pathname`,
  `merge-pathnames`, `probe-file` (now prelude over the renamed `%probe-file`
  primitive; its `BuiltinFunctionWrappers` entry is GONE — the defun serves
  `#'probe-file`), `truename`, `directory` and the `uiop:` walkers,
  `uiop:directory-exists-p`, `uiop:ensure-directory-pathname`,
  `uiop:default-temporary-directory`, `uiop:file-exists-p`,
  `uiop:merge-pathnames*` (interpreter Java + the compile-time fold agree).
- Consumers: `open`/`with-open-file`, `load`, `file-write-date`, `delete-file`,
  `ensure-directories-exist`, `uiop:delete-file-if-exists`,
  `asdf:system-relative-pathname`'s second argument. On the interpreter the
  Java built-ins unwrap via `PathnameOps.designatorNamestring`; on the compile
  paths `open`/`load`/`file-write-date` get their path argument wrapped in
  `LispMacroExpander.coercePathArg` (a `let` + `%obj-is`/`%obj-ref` unwrap,
  primitives only, no splice) — applied ONLY when the instance gate is on, so
  an instance-free program keeps its exact bytes.
- Deliberately still namestrings: `asdf:system-source-directory` /
  `component-pathname` / `system-relative-pathname` / `find-system` answers
  (compile-time locator facts, identical on the interpreter),
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
- `wild-pathname-p`, `pathname-host/device/version`, `translate-pathname`,
  `rename-file`, `*default-pathname-defaults*` still do not exist
  (`.todo/036`).

## Pinning

`LispReaderTest#readPathname*`, `LispEvaluatorTest#pathname*` /
`#directoryFamilyAnswersPathnames` /
`#pathnameDiscriminatesFromStringContentInLackAndJzonShapes` (the lack cond +
jzon typecase + mito check-type shapes), `LispPreludeLibraryTest` (the
PathnameOps agreement pins, now through `#P`),
`Jvm/WasmLispCompilerTest` probe-file/directory/lite-builtins tests, and the
ci-spec cases `pathname-family-and-broadcast-streams` (predicates, printer,
`parse-namestring`, `merge-pathnames`, the lack `finalize-response` body cond
verbatim), `lite-builtins-residue`, `probe-file-existing-and-missing`,
`directory-listing-and-uiop-walkers` (all four backends).
