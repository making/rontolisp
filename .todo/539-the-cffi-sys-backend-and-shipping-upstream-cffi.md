# 539. The `cffi-sys` backend, and shipping upstream CFFI as a built-in system

Difficulty: Medium

Part of `.todo/537`. Needs `.todo/538` (the primitives) and `.todo/540` (the load
blockers). The spike's working version of everything below is
`.todo/537-the-cffi-ecosystem-through-ffm/cffi-rontolisp.lisp` -- written against `java:`
interop; this item rewrites its dozen plumbing functions against `ffi:` and keeps the rest
nearly verbatim.

## The backend file

`cffi-rontolisp.lisp`, a classpath resource beside `usocket.lisp` / `babel.lisp`, in
package `cffi-sys`, implementing the ~30 names `src/package.lisp` exports. Upstream's
`src/cffi-sbcl.lisp` is the model to read; nothing in it is subtle. The parts worth naming:

- **`%foreign-funcall` is a macro whose types are known at expansion**, so it expands to
  one `ffi:call` with quoted type lists -- the shape cache in `.todo/538` then sees the
  same key every time. Same for `%foreign-funcall-pointer` and the two `-varargs`
  siblings, which pass the fixed-prefix length through.
- **Flat namespace.** SBCL and most others `(pushnew 'flat-namespace *features*)`: a
  funcall ignores which library the symbol came from. FFM has no flat namespace of its own
  (`SymbolLookup.libraryLookup` is per-library and the default lookup does not see a
  library opened later), so the backend keeps the loaded lookups in order and searches
  them -- the spike does this and it is what makes `use-foreign-library` + `defcfun`
  behave the way every binding assumes.
- **`*foreign-structures-by-value*`** gets a real implementation over `.todo/538`'s struct
  descriptors instead of upstream's "load cffi-libffi" restart.
- **`with-pointer-to-vector-data`** copies in and out (no pinning); say so in the
  docstring, as the backends that cannot pin do.

## Shipping upstream's source

`(ql:quickload :cffi)` must fetch upstream and load it. Three existing mechanisms carry it:

1. **`AsdOverrides`**: a replacement `cffi.asd`. Upstream's is unloadable here for two
   independent reasons -- it opens with `(error "Sorry, this Lisp is not yet supported")`
   for an implementation it does not recognise, and it ends in a `defmethod
   version-satisfies`, which the `.asd` reader refuses. The replacement declares the same
   components plus `(:file "cffi-rontolisp" :if-feature :rontolisp)`, drops the `test-op`
   clauses, and carries `:rontolisp-features (:64-bit)` (see `.todo/540`).
2. **`ShimLibraries.leafModuleForms`**: `src/strings.lisp` is substituted. It is the one
   portable file that cannot load -- it drives babel's `instantiate-concrete-mappings`
   code generator over per-encoding accessors the shim does not have. The substitute keeps
   the whole surface (`foreign-string-alloc`, `foreign-string-to-lisp`,
   `lisp-string-to-foreign`, `with-foreign-string(s)`,
   `with-foreign-pointer-as-string`, the `:string` and `:string+ptr` types with their
   translate methods) over `babel:string-to-octets` / `octets-to-string`, which the shim
   does have. `.todo/537-.../cffi-strings-substitute.lisp` is a working one.
3. **The backend file itself** ships as a resource and is spliced in as the
   implementation component, so upstream's tree on disk is never edited.

`cffi-grovel` and `cffi-libffi` must FAIL LOUDLY, not silently half-load: a system naming
either in `:defsystem-depends-on` gets a message saying grovelling needs a C toolchain
that rontolisp does not have, and that struct-by-value needs no libffi here. `swank`'s
entry in `ShimLibraries` is the precedent for "a clear message beats letting quicklisp
fetch it".

## The compile paths

`defcfun` expands to `ffi:call`, so a compiled program reaches the primitives the same way
the interpreter does -- what travels, and what refuses, is `.todo/541`. Nothing in this
item is per-backend.

## Acceptance

`(ql:quickload :cffi)` on a clean cache, then
`.todo/537-the-cffi-ecosystem-through-ffm/use.lisp` verbatim, on the interpreter and on
the native binary. A `CffiSystemTest` pinning that upstream's portable files load
unmodified -- that is the property this whole item exists to keep, and it is what will
break the day upstream changes a file.
