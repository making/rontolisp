# CLOS-aware shaking: teach the pruner roots AND valueFuncIds what a method needs

Difficulty: High

Split out of the shrink-the-clack-module item when its other levers landed
(2026-08-09; results in `.kb/optimize-dead-code-elimination.md`). This is the one
lever with an order-of-magnitude ceiling, and it was always "a separate item, not
a tweak" (`.kb/library-defun-pruning.md`, "What stays a root").

## The measured ceiling

todo-295's routed Worker probe (hello-clack + three tiny-routes routes over the
REAL tiny-routes, so cl-ppcre is loaded), 2026-08-08: with a ppcre-free matcher
shimmed in so ZERO references to the engine remain, only 0.9% of the module
leaves. The shim-vs-no-dependency delta — **823,589 B raw, 648 functions,
800,319 B code, 22,392 B data** — is what a loaded-but-unreferenced CLOS-heavy
library costs a module today.

## Why both halves must move together

- **AST level:** `LibraryDefunPruner` keeps every
  `defclass`/`defgeneric`/`defmethod`/`define-condition`/`defstruct` as a root
  (`.kb/library-defun-pruning.md`), so the method bodies survive into codegen
  whatever the program references.
- **Module level:** every surviving method body is materialized as a closure at
  load time, so it lands in `Ctx.valueFuncIds`, is dispatchable, and the
  arity-dispatch ladders hold a real `call` edge to it — the tree shakers are
  RIGHT to keep it. Fixing only the pruner half changes nothing (the closures
  remain); fixing only the shaker half is unsound (a generic call could still
  dispatch there).

A method-aware reachability would have to argue from applicability: a
`defmethod` is reachable only if some reachable call site's argument classes can
select it (specializer classes instantiable from reachable code). Note the
boundary measured in `.kb/optimize-dead-code-elimination.md` ("Lever 2 (CLOS-aware
shaking) cannot pay on a USING app"): for a library whose generics ARE its
pipeline over runtime data (cl-ppcre's parse tree), nearly every method stays
reachable — the win exists only for loaded-but-unreferenced (or
partially-referenced) CLOS surfaces. The routed-Worker case that motivated this
is now served by `tiny-routes/lite` instead, so re-check that a real program
class still needs this before building it.

## Non-goals

- Reopening the funcall-dispatch gate semantics (settled and test-pinned).
- The `%seq-to-*`/CLOS-lowering density levers (landed; see
  `.kb/seq-conversion-runtime.md` and `.kb/clos.md`).
