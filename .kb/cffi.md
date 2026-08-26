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
for the `ffi:` primitives underneath and `.todo/537` for the umbrella.

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

Interpreter only today. Both WASM backends refuse a program that reaches `ffi:` (and so
one that reaches `cffi:`) permanently, by name, in `CompileFrontend`. The native binary's
downcall-shape registration and the JVM class output's embedded blob are `.todo/541`.
`cffi:defcvar` does not work at all: it expands into `define-symbol-macro`, which
rontolisp does not have (`.todo/546`).

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
| `defcfun` / `foreign-funcall` / out parameters / strings / the flat namespace / struct by value / a callback / varargs / a shareable vector | `eval/CffiSystemTest` (skipped where the JVM denies native access) |
| `cffi-grovel` and `cffi-libffi` refuse with the reason | `eval/CffiSystemTest.grovelAndLibffiRefuseWithTheReason` |
| the vendored upstream source (2026-01-01 release, MIT) | `src/test/resources/cffi` |

Docs: `guides/cffi.md` (both language trees), the `cffi` row in
`guides/asdf-systems.md`'s "What can I actually load?".
