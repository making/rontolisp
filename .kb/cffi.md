# Upstream CFFI, running here

**The invariant**: rontolisp does not have a CFFI-shaped API -- it runs **upstream
CFFI's own source**, and the only file it writes is the one every implementation has to
write for itself, the `cffi-sys` backend. Everything above that seam (the type system,
`defcfun`'s argument walker, the enum/bitfield layers, the translate/expand protocol,
`libraries.lisp`, `foreign-vars.lisp`, `features.lisp`) is upstream's code, loaded from
the release tarball unmodified. That property is what the item exists to keep, and
`eval/CffiSystemTest.upstreamsPortableSourceLoadsUnmodified` is what breaks the day
upstream changes a file -- the fix is then to refresh the vendored copy under
`src/test/resources/cffi` and see what the backend owes it.

The reason is the ASSETS, not an FFI: cl+ssl, cl-sqlite, static-vectors, cl-charms and the
whole binding half of Quicklisp is `(:depends-on :cffi)` and `defcfun`. See `.kb/ffi.md`
for the `ffi:` primitives underneath (todo-537 and its five children, 2026-08-26).

## The three bundled pieces, and nothing else

| piece | where | why |
|---|---|---|
| the `cffi-sys` backend | `eval/cffi-rontolisp.lisp` (resource), spliced as the component `src/cffi-rontolisp.lisp` by `ShimLibraries.LEAF_MODULES` | the implementation seam; the file exists ONLY as a resource, so upstream's tree on disk is never edited |
| a replacement `cffi.asd` | `eval/cffi-rontolisp.asd`, `AsdOverrides` | upstream's opens with `(error "Sorry, this Lisp is not yet supported")` for an implementation its own list does not name, and ends in a `defmethod version-satisfies` -- unreadable as data twice over |
| a substitute for `src/strings.lisp` | `eval/cffi-strings.lisp`, `ShimLibraries.LEAF_MODULES` | the ONE portable file that cannot load: it drives babel's `instantiate-concrete-mappings` code generator over per-encoding accessors the babel shim does not have. The substitute keeps the whole surface over `babel:string-to-octets` / `octets-to-string`, which the shim does have |

`:64-bit` -- which `types.lisp` reads to give `:size` a base type -- is NOT declared by
the replacement `.asd`. It comes from `:defsystem-depends-on (:trivial-features)`, which
is upstream's own route and is decided in `BuiltinSystems.DECLARED_FEATURES`
(`.kb/asdf.md`).

## The backend's four decisions

- **The flat namespace is a list this file keeps.** SBCL and most others push
  `cffi-features:flat-namespace` and let a call find a symbol whichever library it came
  from. FFM has no such thing: `SymbolLookup.libraryLookup` is per library and the
  process's own lookup never sees a library opened later. So `%load-foreign-library`
  APPENDS its `ffi:open` handle to `cffi-sys::*libraries*` and `%foreign-symbol-pointer`
  walks the process plus that list, in load order, memoizing hits in
  `cffi-sys::*symbols*`. Without it `use-foreign-library` followed by a plain `defcfun`
  would not resolve -- which every binding in the ecosystem assumes it does.
- **The process handle is fetched lazily, not at load time.** `*libraries*` starts EMPTY
  and `%search-order` conses `(ffi:open)` on at lookup time. Opening the process is a
  foreign operation, so doing it in a load-time `defvar` would make the whole system
  unloadable on a machine that denies native access. As written, loading cffi is reading
  and CLOS, and only a CALL is foreign -- which is why the load test needs no
  `assumeTrue`.
- **`*foreign-structures-by-value*` is the ordinary call path.** A `(:struct name)` ctype
  translates to an `ffi:` `(:struct member...)` designator (`%ffi-type`, walking
  `cffi::slots-in-order` so the members are in OFFSET order -- `cffi:foreign-slot-names`
  is documented "in no particular order" and is a hash walk), and FFM builds the layout.
  Upstream's "Unable to call structures by value without cffi-libffi loaded" restart can
  never fire. The guard: `%struct-designator` refuses by name when cffi's own size and
  alignment disagree with the designator's (a hand-written `:offset`, a bitfield), rather
  than passing on a guess. Set with `defparameter` in the backend file, which loads
  BEFORE `functions.lisp` -- that file's `defvar` then leaves the value in place, which is
  how a backend gets to decide a variable its consumer has not declared yet.
- **The shape is memoized, the symbol is memoized.** `%foreign-funcall` is a macro whose
  types are known at expansion, so it quotes them and one `ffi:call` shape key reaches the
  runtime every time (~24 us to build a downcall handle, ~0.5 us to call one). `%shape`
  memoizes the cffi-type -> `ffi:`-designator translation by `equal` on the pair, and
  `%foreign-symbol-pointer` memoizes name -> address.

Varargs: `%foreign-funcall-varargs` splices `&optional` between the fixed prefix and the
tail, and `foreign-funcall-type-and-args` turns it into the `ffi:` `:varargs` marker --
one spelling for "the variadic tail starts here", which is what makes the call right on
AArch64 and Apple silicon. `with-pointer-to-vector-data` copies IN AND OUT (nothing here
pins a Lisp vector), the way the other unpinnable backends document. A redefined
`defcallback` answers a NEW address: an FFM upcall stub cannot be re-targeted.

## What refuses, and where it runs

`cffi-grovel` and `cffi-libffi` are in `ShimLibraries.REFUSED`, checked at the top of both
loaders (`LispEvaluator.loadSystem`, `cli.LoadInliner.spliceSystem`) beside the conflict
check: grovelling compiles and runs a C program to read the platform's headers, and
struct-by-value needs no libffi here. The `swank` precedent -- a clear message beats
letting `ql:quickload` fetch something that then dies unparsed.

Both WASM backends refuse a program that reaches `ffi:` (and so one that reaches
`cffi:`) permanently, by name, in `CompileFrontend`. Everywhere else it runs: the native
binary interprets it against the registered downcall/upcall shape GRID (`.kb/ffi.md` --
the carrier canonicalisation is what makes the grid finite, and a shape outside it
signals the one metadata entry that would register it; `%call-symbol` re-signals that
miss with the function's name in front), and the JVM class output embeds the whole
binding (`JvmFfiRuntimeBuilder`), so `use.lisp`-shaped programs compile to a `.class`
and answer the interpreter byte for byte. Three compile-path facts the cffi source
forced into existence, each pinned in `JvmFfiInteropCompilerTest`: `%call-address` calls
the internal `ffi:%apply-call` (ffi:call with the arguments as ONE list) instead of
`(apply #'ffi:call ...)`, because the compiled backends give `ffi:` no first-class
function values; `(fboundp 'name)` over a name the macro-time evaluator defined AS A
MACRO folds to `t` in `UserMacroExpander` (macros do not exist in a compiled program, so
the runtime probe would answer nil and functions.lisp's guarded fallback
`%foreign-funcall-varargs` defmacro would run -- and die -- at run time); and
`(setf (apply #'aref ...))` (CLHS 5.1.2.5, foreign-array-to-lisp's runtime-rank store)
lowers through the row-major place in `LispMacroExpander.expandSetf`.
`cffi:defcvar` works: its expansion generates the accessor pair and then
`define-symbol-macro` (`.kb/symbol-macrolet.md`), so the lisp name reads and writes like
a variable while being a call. The generated SETTER writes through `(setf (mem-ref ...))`
— a place whose accessor carries an open-coding compiler macro AND a
`define-setf-expander`, which is the shape that forced `UserMacroExpander`'s setf case to
expand before it walks.

## The consumers, probed

One row per CFFI consumer actually tried, from runs on `java -jar` (Linux x86-64,
2026-08-26) -- not from expectation. The point of the item is the ASSETS, so this table
is the item's own scoreboard.

| library | result | what happened |
|---|---|---|
| **cl-sqlite** (`sqlite`) | **loads and runs** | `(ql:quickload "sqlite")` then a live database: table, inserts, an update, `execute-to-list` / `execute-single`, `with-transaction`, and a prepared statement stepped by hand (`examples/jvm/cffi-sqlite.lisp`). Two gaps outside cffi had to close first -- `(coerce 0 type)` with a COMPUTED type (iterate's `make-initial-value`, which every `iter` clause with a `:type` reaches) now follows CLHS's "already of that type" rule, and an address at or above 2^63 is accepted as the unsigned integer it is (`SQLITE_TRANSIENT` is `(mod -1 (expt 2 64))`). **Interpreter and native binary only**: see the `defcenum` limit below |
| **static-vectors** | **does not load, and cannot** | not a CFFI consumer at all but a SECOND implementation seam: its `.asd` opens with `(error "static-vectors does not support this Common Lisp implementation!")` under `#-(or abcl allegro ... sbcl)`, and past that the system needs an `impl-<lisp>.lisp` supplying a vector whose storage is non-moving memory a pointer can be taken into. rontolisp has no such array type -- `with-pointer-to-vector-data` copies in and out here -- so an `impl-rontolisp.lisp` could not keep the library's one promise. Not worth a shim: what a consumer wants from it (`fast-io`'s buffers) is reachable through `cffi:foreign-alloc` directly |
| **cl+ssl** (the real one) | **does not load** -- and the FFI layer was never reached | a probe, never a migration: the `cl+ssl` shim over `rontolisp:tls-upgrade` stays the default (`.kb/tcp-sockets.md`), because it works on the WASM component backend and needs no OpenSSL. Every blocker found was in a DEPENDENCY SHIM, none in cffi. In order: (1) `defpackage :cl+ssl` signals, because rontolisp pre-registers `CL+SSL` for its own shim -- probed past by renaming the package in a scratch copy; (2) flexi-streams had no in-memory OUTPUT stream (`make-in-memory-output-stream` / `get-output-stream-sequence`) -- ADDED; (3) trivial-garbage had no `make-weak-hash-table` -- ADDED (weakness is not observable from CL, so it degrades to an ordinary table); (4) bordeaux-threads had no `make-recursive-lock` / `with-recursive-lock-held` -- ADDED (the shim's `make-lock` is already reentrant, so the pair is one object and one expansion); (5) `flexi-streams:flexi-stream` as a real WRAPPER CLASS with `flexi-stream-stream`, which the shim deliberately does not have -- a flexi stream here IS the underlying stream. That fifth one is where the probe stopped; `.todo/550` carries the rest |

`cffi-grovel` consumers were not probed and never will be: grovelling compiles and runs a
C program to read the platform's headers.

## The `defcenum` limit: cffi relies on `make-load-form`

A `defcfun` whose return or argument type is a `defcenum` (or any other
`translatable-foreign-type`) expands to a form containing the foreign-type OBJECT itself
-- `expand-to-foreign` splices `,type` -- and upstream makes that legal by defining
`(defmethod make-load-form ((type foreign-type)) `(parse-type ',(unparse-type type)))`
in `early-types.lisp`. The interpreter is fine: the object is live. **The compile paths
are not**: they quote a literal by structure, the enum object's slots hold hash tables,
and the program dies with `Cannot quote: #<HASH-TABLE ...>` -- so honoring
`make-load-form` for a literal instance (`.todo/549`) is what the enum-using half of the
ecosystem waits on. cl-sqlite's every entry point returns a `defcenum`, which is why its example
declares the interpreter only. An enum-free binding (the `use.lisp` shape) still compiles
to a `.class` and answers the interpreter byte for byte.

## Leaf modules may now select a package

`leafModuleForms` shims used to have to be in canonical shape (qualified names, no
`in-package`) because both loaders spliced them bare. The cffi backend is a near-verbatim
analogue of upstream's own `cffi-sbcl.lisp` and opens with `(in-package #:cffi-sys)`, so
both loaders now BRACKET a leaf shim the way they bracket a real component:
`packageResolver.pushPackage()`/`popPackage()` in the interpreter, and the
`%push-package`/`%pop-package` markers on the compile path, emitted only when
`selectsAPackage` is true -- so every existing shim's output is unchanged.

## Tests

| what | where |
|---|---|
| upstream's portable source loads unmodified, needing NO native access; the type/enum/struct layers answer; `*foreign-structures-by-value*` is replaced | `eval/CffiSystemTest` |
| `defcfun` / `foreign-funcall` / out parameters / strings / the flat namespace / struct by value / a callback / varargs / a shareable vector / `defcvar` over a real C global | `eval/CffiSystemTest` (skipped where the JVM denies native access) |
| `cffi-grovel` and `cffi-libffi` refuse with the reason | `eval/CffiSystemTest.grovelAndLibffiRefuseWithTheReason` |
| the vendored upstream source (2026-01-01 release, MIT) | `src/test/resources/cffi` |

Docs: `guides/cffi.md` (both language trees), the `cffi` row in
`guides/asdf-systems.md`'s "What can I actually load?".
