# Upstream CFFI, running here

**Invariant**: rontolisp does not have a CFFI-shaped API — it runs **upstream CFFI's own source**, and the only file it writes is the `cffi-sys` backend every implementation must write for itself. Everything above that seam (the type system, `defcfun`'s argument walker, the enum/bitfield layers, the translate/expand protocol, `libraries.lisp`, `foreign-vars.lisp`, `features.lisp`) is upstream's code, loaded from the release tarball unmodified. `eval/CffiSystemTest.upstreamsPortableSourceLoadsUnmodified` breaks the day upstream changes a file; the fix is then to refresh the vendored copy under `src/test/resources/cffi` and see what the backend owes it.

Motivation is the ASSETS: cl+ssl, cl-sqlite, static-vectors, cl-charms and the whole binding half of Quicklisp is `(:depends-on :cffi)` + `defcfun`. The `ffi:` primitives underneath: `.kb/ffi.md`.

## The three bundled pieces, and nothing else

| piece | where | why |
|---|---|---|
| the `cffi-sys` backend | `eval/cffi-rontolisp.lisp` (resource), spliced as component `src/cffi-rontolisp.lisp` by `ShimLibraries.LEAF_MODULES` | the implementation seam; resource-only, so upstream's on-disk tree is never edited |
| a replacement `cffi.asd` | `eval/cffi-rontolisp.asd`, `AsdOverrides` | upstream's opens with `(error "Sorry, this Lisp is not yet supported")` for an unnamed implementation and ends in a `defmethod version-satisfies` — unreadable as data twice over |
| a substitute for `src/strings.lisp` | `eval/cffi-strings.lisp`, `ShimLibraries.LEAF_MODULES` | the ONE portable file that cannot load: it drives babel's `instantiate-concrete-mappings` code generator over per-encoding accessors the babel shim lacks. The substitute keeps the whole surface over `babel:string-to-octets` / `octets-to-string` |

`:64-bit` (which `types.lisp` reads to give `:size` a base type) is NOT declared by the replacement `.asd`; it comes from `:defsystem-depends-on (:trivial-features)`, decided in `BuiltinSystems.DECLARED_FEATURES` (`.kb/asdf.md`).

## The host is part of the announcement

The same route carries the MACHINE (`.kb/asdf.md`, "The HOST half is a probe"): `:darwin` + `:bsd` or `:linux`, and `:arm64` or `:x86-64`, on the JVM family only. cffi is why it exists — every step of library resolution is `featurep`:

- `%foreign-library-spec` picks the FIRST clause `featurep` accepts, so cl-sqlite's `(:darwin (:default "libsqlite3")) (:unix (:or "libsqlite3.so.0" "libsqlite3.so"))` resolved to the Linux names on a Mac while only `:unix` was announced (`Unable to load any of the alternatives`). Every `define-foreign-library` in the ecosystem is shaped this way.
- `default-library-suffix` is `assoc-if #'featurep` over `*cffi-feature-suffix-map*`: `:default` means `.dylib` here and `.so` there.
- `darwin-frameworks.lisp` is `:if-feature :darwin` in the replacement `.asd` (which keeps upstream's `(:feature :darwin :uiop)` dependency for it). Loading it gives `*foreign-library-directories*` the DYLD fallback list — and `/opt/homebrew/lib` is in that list behind `#+arm64`. dyld does not search Homebrew's prefix itself, so without the CPU name a brew-installed library is unreachable even when `:darwin` is right.

Pinned by `CffiSystemTest.theHostsOwnOsPicksTheLibraryClauseAndTheDefaultSuffix` (clause, suffix, darwin component, no native access needed) and `AsdfSystemsTest.parsesTheBundledCffiReplacementAsd`, whose component list is host-shaped for this reason.

**Trap: a variadic call spelled as a plain `foreign-funcall` with every argument FIXED holds on Linux x86-64 and CANNOT on Apple silicon**, where a variadic argument goes on the stack whatever its type. The correct spelling is `foreign-funcall-varargs` (fixed prefix and tail arrive separately; `%foreign-funcall-varargs` turns the split into the `ffi:` `:varargs` marker). `CffiSystemTest.aCallbackAndAVariadicCallReachTheForeignSide` asserted the accident until a Mac ran it.

## The backend's four decisions

- **The flat namespace is a list this file keeps.** SBCL pushes `cffi-features:flat-namespace`; FFM has no such thing (`SymbolLookup.libraryLookup` is per library and the process lookup never sees a library opened later). So `%load-foreign-library` APPENDS its `ffi:open` handle to `cffi-sys::*libraries*` and `%foreign-symbol-pointer` walks the process plus that list in load order, memoizing hits in `cffi-sys::*symbols*`. Without it `use-foreign-library` followed by a plain `defcfun` would not resolve.
- **The process handle is fetched lazily, not at load time.** `*libraries*` starts EMPTY and `%search-order` conses `(ffi:open)` on at lookup time. Opening the process is a foreign operation; doing it in a load-time `defvar` would make the system unloadable where native access is denied. As written, loading cffi is reading and CLOS and only a CALL is foreign — which is why the load test needs no `assumeTrue`.
- **`*foreign-structures-by-value*` is the ordinary call path.** A `(:struct name)` ctype translates to an `ffi:` `(:struct member...)` designator (`%ffi-type`, walking `cffi::slots-in-order` so members are in OFFSET order — `cffi:foreign-slot-names` is documented "in no particular order" and is a hash walk), and FFM builds the layout. Upstream's "Unable to call structures by value without cffi-libffi loaded" restart can never fire. Guard: `%struct-designator` refuses BY NAME when cffi's own size and alignment disagree with the designator's (a hand-written `:offset`, a bitfield) rather than passing on a guess. Set with `defparameter` in the backend file, which loads BEFORE `functions.lisp` — that file's `defvar` then leaves the value in place.
- **The shape and the symbol are memoized.** `%foreign-funcall` is a macro whose types are known at expansion, so it quotes them and one `ffi:call` shape key reaches the runtime every time (~24 us to build a downcall handle, ~0.5 us to call one). `%shape` memoizes cffi-type -> `ffi:`-designator by `equal` on the pair; `%foreign-symbol-pointer` memoizes name -> address.

Also: `%foreign-funcall-varargs` splices `&optional` between fixed prefix and tail, and `foreign-funcall-type-and-args` turns it into the `ffi:` `:varargs` marker. `with-pointer-to-vector-data` copies IN AND OUT (nothing here pins a Lisp vector). A redefined `defcallback` answers a NEW address: an FFM upcall stub cannot be re-targeted.

## What refuses, and where it runs

- `cffi-grovel` and `cffi-libffi` are in `ShimLibraries.REFUSED`, checked at the top of both loaders (`LispEvaluator.loadSystem`, `cli.LoadInliner.spliceSystem`) beside the conflict check: grovelling compiles and runs a C program to read platform headers, and struct-by-value needs no libffi here.
- Both WASM backends refuse a program reaching `ffi:` (hence `cffi:`) permanently, by name, in `CompileFrontend`.
- The native binary interprets it against the registered downcall/upcall shape GRID (`.kb/ffi.md`); a shape outside it signals the one metadata entry that would register it, and `%call-symbol` re-signals that miss with the function's name in front. A `defcfun` returning a struct BY VALUE is inside the grid.
- The JVM class output embeds the whole binding (`JvmFfiRuntimeBuilder`), so `use.lisp`-shaped programs compile to a `.class` and answer the interpreter byte for byte.

Three compile-path facts the cffi source forced into existence, each pinned in `JvmFfiInteropCompilerTest`:
- `%call-address` calls the internal `ffi:%apply-call` (ffi:call with the arguments as ONE list) instead of `(apply #'ffi:call ...)`, because the compiled backends give `ffi:` no first-class function values.
- `(fboundp 'name)` over a name the macro-time evaluator defined AS A MACRO folds to `t` in `UserMacroExpander` — macros do not exist in a compiled program, so the runtime probe would answer nil and functions.lisp's guarded fallback `%foreign-funcall-varargs` defmacro would run, and die, at run time.
- `(setf (apply #'aref ...))` (CLHS 5.1.2.5, foreign-array-to-lisp's runtime-rank store) lowers through the row-major place in `LispMacroExpander.expandSetf`.

`cffi:defcvar` works: its expansion generates the accessor pair then `define-symbol-macro` (`.kb/symbol-macrolet.md`), so the lisp name reads and writes like a variable while being a call. The generated SETTER writes through `(setf (mem-ref ...))` — a place whose accessor carries an open-coding compiler macro AND a `define-setf-expander`, the shape that forced `UserMacroExpander`'s setf case to expand before it walks.

## `defcenum` and `make-load-form`

A `defcfun` whose return or argument type is a `defcenum` (or any `translatable-foreign-type`) expands to a form containing the foreign-type OBJECT itself (`expand-to-foreign` splices `,type`); upstream makes that legal with `(defmethod make-load-form ((type foreign-type)) ...)` in `early-types.lisp`. The interpreter is fine. The compile paths used to quote such a literal by STRUCTURE, reach the enum's hash-table slots and die with `Cannot quote: #<HASH-TABLE ...>`; they now honor the method (`.kb/make-load-form.md`), which gives `examples/jvm/cffi-sqlite.lisp` its `jvm` leg.

## Leaf modules may select a package

`leafModuleForms` shims used to have to be in canonical shape (qualified names, no `in-package`). The cffi backend is a near-verbatim analogue of upstream's `cffi-sbcl.lisp` and opens with `(in-package #:cffi-sys)`, so both loaders now BRACKET a leaf shim like a real component: `packageResolver.pushPackage()`/`popPackage()` in the interpreter, `%push-package`/`%pop-package` markers on the compile path, emitted only when `selectsAPackage` is true — so every existing shim's output is unchanged.

## The consumers, probed

| library | result | notes |
|---|---|---|
| **cl-sqlite** (`sqlite`) | loads and runs (Linux x86-64, macOS arm64) | live database via `examples/jvm/cffi-sqlite.lisp`; also compiles to a `.class`. Two non-cffi gaps had to close first: `(coerce 0 type)` with a COMPUTED type (iterate's `make-initial-value`) now follows CLHS's "already of that type" rule, and an address at or above 2^63 is accepted as the unsigned integer it is (`SQLITE_TRANSIENT` is `(mod -1 (expt 2 64))`) |
| **static-vectors** | does not load, and cannot | a SECOND implementation seam, not a cffi consumer: its `.asd` errors under `#-(or abcl allegro ... sbcl)` and past that it needs an `impl-<lisp>.lisp` supplying a vector in non-moving memory. rontolisp has no such array type (`with-pointer-to-vector-data` copies), so an `impl-rontolisp.lisp` could not keep the library's one promise. What consumers want from it (`fast-io`'s buffers) is reachable through `cffi:foreign-alloc` |
| **cl+ssl** (the real one) | LOADS, completes a real TLS handshake, and round-trips a real HTTPS request | a probe, never a migration |

`cffi-grovel` consumers were not probed and never will be.

### cl+ssl, in detail

Not one blocker was ever in cffi. Five stood between the library and a load, all fixed generally: (1) `defpackage :cl+ssl` signalled because rontolisp pre-registers `CL+SSL` for its shim — `defpackage` over an existing package now MODIFIES it as CLHS requires (`.kb/packages.md`); (2) flexi-streams had no in-memory OUTPUT stream (`make-in-memory-output-stream` / `get-output-stream-sequence`); (3) trivial-garbage had no `make-weak-hash-table` (weakness is not observable from CL, so it degrades to an ordinary table); (4) bordeaux-threads had no `make-recursive-lock` / `with-recursive-lock-held` (the shim's `make-lock` is already reentrant, so the pair is one object and one expansion); (5) `flexi-streams:flexi-stream` as a real WRAPPER CLASS with `flexi-stream-stream` (`.kb/gray-streams.md`).

`SSL_connect` completes a real handshake through cl+ssl's LISP BIO — OpenSSL calling back into Lisp through FFM upcalls (`bio.lisp`'s `lisp-read`/`lisp-write`/`lisp-ctrl` `defcallback`s) for every handshake octet, over `read-byte`/`write-byte` on a rontolisp socket. Two further blockers, again language gaps not binding ones:

- **A rontolisp stream used to BE an integer.** `install-handle-and-bio` chooses its BIO with `(etypecase socket (integer (ssl-set-fd handle socket)) (stream (ssl-set-bio ... (bio-new-lisp) ...)))` and cl+ssl's `(defmethod stream-fd (stream) stream)` hands the stream back. A small-integer stream handle took the `integer` arm, so OpenSSL was told to use handle 3 as a socket descriptor (`SSL_get_error: 5`, empty error queue); `:unwrap-stream-p nil` did not help because the etypecase dispatches on the VALUE. Closed in two steps: a Gray stream now answers `streamp`/`(typep x 'stream)` and the compile paths stopped PRUNING such an etypecase's `stream` arm (`.kb/gray-streams.md`), and every OPEN stream is now a self-describing value so a rontolisp socket handed over DIRECTLY reaches the Lisp BIO by dispatch (`.kb/read-load-streams.md`, "A stream is a VALUE").
- An `(eql +constant+)` specializer used to be taken as the SYMBOL, so `x509.lisp`'s `(defmethod decode-asn1-string (asn1-string (type (eql +v-asn1-iastring+))))` never applied to the `22` a certificate yields. A bare name now resolves through the registry's `defconstant` table (`.kb/clos.md`, dispatcher section).

With both fixed, a `GET https://example.com/` over `(cl+ssl:make-ssl-client-stream (usocket:socket-stream (usocket:socket-connect ...)) ...)` reads back a real `HTTP/1.1 200 OK`, on `java -jar` and on the native binary (`--system-path` over a scratch copy of the release, its `.asd`'s system renamed to dodge the bundled shim's system name — the package stays `cl+ssl`, so consumer code is unchanged). The bundled `cl+ssl` shim over `rontolisp:tls-upgrade` (`.kb/tcp-sockets.md`, `guides/asdf-systems.md#built-in-shim-systems`) stays the default regardless: it needs no OpenSSL and is the only one of the two that works on the WASM component backend, where CFFI never will.

## Tests

| what | where |
|---|---|
| upstream's portable source loads unmodified with NO native access; the type/enum/struct layers answer; `*foreign-structures-by-value*` is replaced | `eval/CffiSystemTest` |
| `defcfun` / `foreign-funcall` / out parameters / strings / the flat namespace / struct by value / a callback / varargs / a shareable vector / `defcvar` over a real C global | `eval/CffiSystemTest` (skipped where the JVM denies native access) |
| `cffi-grovel` and `cffi-libffi` refuse with the reason | `eval/CffiSystemTest.grovelAndLibffiRefuseWithTheReason` |
| the vendored upstream source (2026-01-01 release, MIT) | `src/test/resources/cffi` |

Docs: `guides/cffi.md` (both language trees), the `cffi` row in `guides/asdf-systems.md`'s "What can I actually load?".
