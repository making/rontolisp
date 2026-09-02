# `adjust-array` on a displaced array signals where SBCL un-displaces it

Difficulty: Low

Found 2026-09-02 while closing `.todo/647` (a displaced view carrying its own
fill pointer).

```lisp
(let* ((b (make-array 6 :initial-contents '(10 20 30 40 50 60)))
       (v (make-array 4 :displaced-to b :displaced-index-offset 1 :adjustable t)))
  (adjust-array v 3))
;; rontolisp, all four backends:
;;   adjust-array: displaced arrays are not supported
;; SBCL 2.2.9: the view is adjusted IN PLACE (eq to the argument), keeps the
;;   elements at the subscripts valid in both shapes -- (20 30 40) -- and comes
;;   back UN-DISPLACED: (array-displacement r) => NIL, 0.
```

The refusal is a deliberate lite decision recorded in `.kb/adjustable-arrays.md`
("What is still NOT supported"), and it predates the machinery that would close
it. `.todo/647` added exactly that machinery, because `vector-push-extend` over a
full displaced view needs the same operation:

- interpreter `LispArray.undisplace()` / `LispString.undisplace()`
- JVM `_arrayUndisplace` (`JvmArrayRuntimeBuilder`, in `METHOD_NAMES`)
- wasm `_arr_undisplace` (`FUNC_ARR_UNDISPLACE`, `WasmArrayRuntimeBuilder`)

Each copies the view's current contents into storage of its own, drops the
displacement and carries the shape marker across (the JVM's header length 7 -> 4,
wasm's resolved meta marker word), so a string view stays a string.

The work is therefore wiring, not representation:

- `LispMacroExpander.expandAdjustArray` -- replace the `displacedCheck` arm
  (`(if (%array-disp-target a) (error "adjust-array: displaced arrays are not
  supported"))`, around the `%array-become` expansion) with a call to a new
  internal primitive `%array-undisplace`, which each backend already has a body
  for. That primitive needs the usual five wiring points: `LispNames`,
  `PackageRegistry.CL_INTERNALS`, both `programUsesAnyArrayOp` gate lists, and a
  case in `Jvm`/`WasmExprCompiler.compileCons`.
- `Environment`'s own `adjust-array` built-in -- drop the two
  `displacedTo() != null` refusals (the `LispString` arm's and
  `adjustArray`'s) and call `undisplace()` first. `LispString.adjustCapacity`
  already does.
- Docs: the last sentence of `reference/functions/adjust-array.md` (en+ja) and
  the `:displaced-to` paragraph of `make-array.md` both say a displaced view
  cannot be adjusted.

Pin one case per backend plus a `ci-spec.yaml` line beside
`displaced-fill-pointer-cross-backend`, and diff the answers against SBCL 2.2.9
(`/usr/bin/sbcl`) rather than inventing them -- in particular whether a
NON-adjustable displaced view answers a fresh array (it should, by the same rule
every other non-adjustable argument follows).

Adjacent, deliberately NOT part of this item: `adjust-array` still refuses a
packed integer vector and a packed float array, which is a different decision
with its own reasons (`.kb/adjustable-arrays.md`).
