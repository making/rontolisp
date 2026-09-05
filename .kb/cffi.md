# Upstream CFFI, running here

**Invariant**: rontolisp has no CFFI-shaped API of its own -- it runs **upstream CFFI's source**,
and the only file it writes is the `cffi-sys` backend every implementation must write for itself.
Everything above that seam is upstream's, unmodified.
`eval/CffiSystemTest.upstreamsPortableSourceLoadsUnmodified` breaks the day upstream changes a
file; refresh `src/test/resources/cffi` and see what the backend owes it. The `ffi:` primitives
underneath: `.kb/ffi.md`.

## The three bundled pieces, and nothing else
- the `cffi-sys` backend -- `eval/cffi-rontolisp.lisp` (resource), spliced as component
  `src/cffi-rontolisp.lisp` by `ShimLibraries.LEAF_MODULES`; resource-only, so upstream's tree is
  never edited.
- a replacement `cffi.asd` -- `eval/cffi-rontolisp.asd`, `AsdOverrides`; upstream's is unreadable
  as data (an `(error "Sorry, this Lisp is not yet supported")` and a `defmethod`).
- a substitute for `src/strings.lisp` -- `eval/cffi-strings.lisp`, `ShimLibraries.LEAF_MODULES`;
  the ONE portable file that cannot load (babel's `instantiate-concrete-mappings` generator), kept
  over `babel:string-to-octets` / `octets-to-string`.
- `:64-bit` (read by `types.lisp` for `:size`) comes from
  `:defsystem-depends-on (:trivial-features)` via `BuiltinSystems.DECLARED_FEATURES`
  (`.kb/asdf.md`), not the replacement `.asd`.

## The host is part of the announcement
The same route carries the MACHINE (`.kb/asdf.md`): `:darwin` + `:bsd` or `:linux`, and `:arm64`
or `:x86-64`, JVM family only. cffi is why it exists -- every step of library resolution is
`featurep`: `%foreign-library-spec` picks the FIRST clause `featurep` accepts;
`default-library-suffix` is `assoc-if #'featurep` over `*cffi-feature-suffix-map*`;
`darwin-frameworks.lisp` is `:if-feature :darwin` and gives `*foreign-library-directories*` the
DYLD fallback list, whose `/opt/homebrew/lib` sits behind `#+arm64`. Pinned by
`CffiSystemTest.theHostsOwnOsPicksTheLibraryClauseAndTheDefaultSuffix` and
`AsdfSystemsTest.parsesTheBundledCffiReplacementAsd`.

**Trap: a variadic call spelled as a plain `foreign-funcall` with every argument FIXED holds on
Linux x86-64 and CANNOT on Apple silicon**, where a variadic argument goes on the stack whatever
its type. The correct spelling is `foreign-funcall-varargs`.

## The backend's four decisions
- **The flat namespace is a list this file keeps** (FFM has no process lookup seeing a
  later-opened library): `%load-foreign-library` APPENDS its `ffi:open` handle to
  `cffi-sys::*libraries*`, `%foreign-symbol-pointer` walks the process plus that list in load
  order, memoizing in `cffi-sys::*symbols*`. `*libraries*` starts EMPTY and `%search-order` conses
  `(ffi:open)` on LAZILY, so loading cffi is reading and CLOS and only a CALL is foreign.
- **`*foreign-structures-by-value*` is the ordinary call path**: `%ffi-type` walks
  `cffi::slots-in-order` (OFFSET order -- `cffi:foreign-slot-names` is a hash walk) into an `ffi:`
  `(:struct member...)` designator FFM lays out, so upstream's cffi-libffi restart never fires.
  `%struct-designator` refuses BY NAME when cffi's size/alignment disagree with the designator's.
  Set with `defparameter` in the backend file, which loads BEFORE `functions.lisp`.
- **Shape and symbol are memoized**: `%foreign-funcall` quotes its expansion-time types so one
  `ffi:call` shape key reaches the runtime (~24 us to build a downcall handle, ~0.5 us to call);
  `%shape` by `equal`, `%foreign-symbol-pointer` name -> address.
- `with-pointer-to-vector-data` copies IN AND OUT; a redefined `defcallback` answers a NEW address
  (an FFM upcall stub cannot be re-targeted).

## What refuses, and where it runs
- `cffi-grovel` and `cffi-libffi` are in `ShimLibraries.REFUSED` (checked in
  `LispEvaluator.loadSystem` and `cli.LoadInliner.spliceSystem`). Both WASM backends refuse `ffi:`
  (hence `cffi:`) permanently, by name, in `CompileFrontend`.
- The native binary interprets it against the registered downcall/upcall shape GRID
  (`.kb/ffi.md`); `%call-symbol` re-signals a miss with the function's name in front. A struct
  returned BY VALUE is inside the grid. The JVM class output embeds the whole binding
  (`JvmFfiRuntimeBuilder`).
- Three compile-path facts the cffi source forced, pinned in `JvmFfiInteropCompilerTest`:
  `%call-address` calls internal `ffi:%apply-call` (arguments as ONE list, since compiled backends
  give `ffi:` no first-class function values); `(fboundp 'name)` over a macro-time-defined MACRO
  folds to `t` in `UserMacroExpander`; `(setf (apply #'aref ...))` lowers through the row-major
  place in `LispMacroExpander.expandSetf`.
- `cffi:defcvar` works via `define-symbol-macro` (`.kb/symbol-macrolet.md`); its `(setf (mem-ref
  ...))` setter forced `UserMacroExpander`'s setf case to expand before it walks. A `defcfun` over
  a `translatable-foreign-type` expands to a form containing the type OBJECT, so the compile paths
  honor upstream's `make-load-form` (`.kb/make-load-form.md`).
- **Leaf modules may select a package**: both loaders BRACKET a leaf shim
  (`pushPackage`/`popPackage`, `%push-package`/`%pop-package` markers) when `selectsAPackage`, so
  the backend can open with `(in-package #:cffi-sys)`; existing shims' output is unchanged.

## The consumers, probed
- **cl-sqlite** -- loads and runs (Linux x86-64, macOS arm64), live database via
  `examples/jvm/cffi-sqlite.lisp`, also compiles to a `.class`.
- **static-vectors** -- does not load, and cannot: a SECOND implementation seam needing a vector
  in non-moving memory, which rontolisp has no array type for. Use `cffi:foreign-alloc`.
- **cl+ssl** (the real one) -- LOADS, completes a real TLS handshake through its LISP BIO (OpenSSL
  calling back into Lisp through FFM upcalls) and round-trips a real HTTPS request on `java -jar`
  and the native binary. A probe, never a migration. Not one blocker was in cffi -- all were
  language gaps closed generally, in `.kb/packages.md`, `.kb/gray-streams.md`,
  `.kb/read-load-streams.md` (`install-handle-and-bio`'s `etypecase` must take the `stream` arm)
  and `.kb/clos.md` (an `(eql +constant+)` specializer), plus flexi-streams in-memory output
  streams, `trivial-garbage:make-weak-hash-table` and bordeaux-threads recursive locks.
- The bundled `cl+ssl` shim over `rontolisp:tls-upgrade` (`.kb/tcp-sockets.md`) stays the default:
  no OpenSSL, and the only one that works on the WASM component backend. `cffi-grovel` consumers
  were not probed and never will be.

## Tests
`eval/CffiSystemTest` -- upstream source loads with NO native access; type/enum/struct layers;
`defcfun` / `foreign-funcall` / out parameters / strings / flat namespace / struct by value /
callback / varargs / `defcvar` (skipped where native access is denied);
`grovelAndLibffiRefuseWithTheReason`; `aCallbackAndAVariadicCallReachTheForeignSide`. Vendored
upstream: `src/test/resources/cffi`. Docs: `guides/cffi.md`, `guides/asdf-systems.md`.
