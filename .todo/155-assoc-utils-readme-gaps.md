# assoc-utils README — three examples don't work

Probe date 2026-07-19. `(ql:quickload :assoc-utils)` succeeds (real
`assoc-utils-20241012-git`) and most README examples run, but 3 fail. They
trace to 3 independent infrastructure gaps, each of which affects many CL
libraries, not just this one. Source under probe:
`~/.rontolisp/quicklisp/software/assoc-utils-20241012-git/src/assoc-utils.lisp`.

## README example status (interpreter)

| Example | Status |
|---|---|
| `aget` read / default | OK |
| `(setf (aget ...) ...)` | **FAIL — gap B** |
| `alist-get` | **FAIL — gap A** (returns nil) |
| `with-keys` | **FAIL — gap A** (`variable name is unbound`) |
| `remove-from-alist` / `delete-from-alist` | OK |
| `alist-plist` / `plist-alist` | OK |
| `alist-hash` / `hash-alist` | OK |
| `alist-keys` / `alist-values` | OK |
| `alistp` | OK |
| `(typep ... 'alist)` | **FAIL — gap C** (returns nil, want t) |
| `alist=` | OK |

## Gap A — reader case-folding vs. rontolisp's case-preserving reader

rontolisp keeps symbol case verbatim (deliberate: `symbol-name` is
case-preserving, no intern table). Standard CL's reader `:upcase`s unescaped
symbols, and both the library and the README rely on that folding:

- `alist-get`: data keys are written `:ELEMENTS :TAGS :NOTE` (upper), the query
  is `'(:elements 0 :tags :note)` (lower). In CL both fold to `:ELEMENTS` and
  `assoc` matches; in rontolisp `:elements /= :ELEMENTS`, so `assoc` misses and
  the reduce returns nil. Minimal: `(eq :elements :ELEMENTS)` => nil here.
- `with-keys`: the macro binds `(intern (string-upcase (format nil "~A" entry)))`
  => `NAME` (upper) but the body references `name` (lower). CL folds the body
  ref to `NAME`; rontolisp leaves them distinct => `variable name is unbound`.

This is the widest-reaching and most design-loaded of the three: any CL code
that mixes symbol case and leans on reader folding hits it. Options span from
"document as a known incompatibility" to "add a readtable-case-style upcasing
mode". Needs a design decision before any code — do NOT just flip the reader;
case-preservation is load-bearing across the existing test corpus.

## Gap B — `define-setf-expander` is a no-op, so user setf places fail

`assoc-utils` gives `aget` a setf via `define-setf-expander`. rontolisp's
`LispMacroExpander.expandDefineSetfExpander()` (LispMacroExpander.java:11789)
intentionally expands it to `nil`, so the place is unusable:
`setf does not support place: assoc-utils:aget`. The full five-value
expansion protocol (`get-setf-expansion` / `&environment`) is unimplemented.
Feature add, not a bug fix; also unlocks `defsetf` short/long forms if done
generally. Interpreter first, then both compilers' setf front-ends.

## Gap C — `typep` doesn't resolve a user `deftype` that expands to `satisfies`

`(deftype alist () '(satisfies alistp))` is accepted, but `typep` never
consults user deftypes, so `(typep x 'alist)` is always nil even though
`(alistp x)` is correct. Minimal repro:
```lisp
(deftype my-even () '(satisfies evenp))
(typep 4 'my-even)  ; => nil, want t
```
Smallest/most local of the three: `typep` needs a user-deftype registry +
`satisfies` handling (call the named predicate). Shared static type-test
builder is in `LispMacroExpander` (see `typep`/`subtypep` machinery); the
compilers fold literal type specifiers, so a user deftype has to be expanded
at that layer too, or fall through to a runtime predicate call.

## Suggested order

C (local, clear cost) -> B (feature, bounded) -> A (design decision first).
Each is independently shippable; none blocks the others.
