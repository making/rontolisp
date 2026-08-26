# 540. The four gaps that stop upstream CFFI from loading

Difficulty: Low

Part of `.todo/537`, and the only child that stands on its own: each gap is a real hole
someone else will hit, and none of them needs the foreign primitives. Do this one first --
it is what turns the spike's four local patches into nothing.

Found by loading upstream cffi form by form
(`.todo/537-the-cffi-ecosystem-through-ffm/README.md`, "What the load probe found"). Every
other portable file loaded unmodified.

## 1. `subsetp` does not exist

Not in `LispNames`, not in `Environment`, not on any backend: `(subsetp '(1 2) '(1 2 3))`
answers "The function SUBSETP is undefined". `cffi`'s `define-foreign-library` calls it,
and it is plain CL (`subsetp list1 list2 &key key test test-not`). The full "Adding a
Built-in Function" walk in `CLAUDE.md` applies -- both compilers, the wrapper entry, the
per-operator doc page in both languages, a `ci-spec.yaml` case.

## 2. The babel shim is missing cffi's string seam

`src/strings.lisp` reads `babel:unicode-string`, `babel:simple-unicode-string`,
`babel:unicode-char` (only the last exists) and `babel::string-get` / `string-set`, then
drives `babel:instantiate-concrete-mappings`. Add the three types and the two accessors to
`babel.lisp` and export them. The code generator is a different question and is answered
by substituting cffi's `strings.lisp` instead (`.todo/539`); do NOT try to reimplement it.

## 3. `asdf:operate` is not external

`functions.lisp` reads `(asdf:operate 'asdf:load-op 'cffi-libffi)` inside a restart body
that never runs here, and the READ fails: "The symbol OPERATE is not external in the ASDF
package". Add `operate` (and `load-op`, which the same form names) to the `asdf` package's
externals in `PackageRegistry` -- resolve-only, like the missing-component condition and
restart names already listed there for dbi's sake.

## 4. `:64-bit` is announced by nobody

`types.lisp` ends with `(defctype :size #+64-bit :uint64 #+32-bit :uint32)`. Neither
feature exists here, so the form loses its base type and cffi's own `check-type` fires --
after the other 1071 lines have loaded. `.todo/539`'s replacement `.asd` can carry
`:rontolisp-features (:64-bit)`, but the better fix is upstream's own logic: the
`trivial-features` shim already declares `:unix` and `:little-endian` for a depending
system (`doc/{en,ja}/guides/asdf-systems.md`) and deliberately declares no CPU names. Every
rontolisp backend has 64-bit fixnums and 8-byte pointers wherever pointers exist at all, so
`:64-bit` is as true here as `:little-endian` is -- decide it in `trivial-features` for
everyone, and record the reason beside the existing two. `:32-bit` stays absent.

## Acceptance

The spike re-run with none of its four local patches: `subsetp` from a bare REPL on all
four backends, and upstream cffi's `types.lisp`, `strings.lisp` and `functions.lisp`
loading with only the `strings.lisp` substitution of `.todo/539` in place.
