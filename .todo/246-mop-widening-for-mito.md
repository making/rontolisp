# MOP protocol widening for mito's metaclasses (incl. todo-230)

Difficulty: 高 (extends the static metaclass protocol of `.kb/clos.md` Phase B
across interpreter + compile paths; class REdefinition is a genuinely new
capability — recommend a Fable-class model)

Part of the Mito milestone `.todo/238` (substrate; independent of the library
chain, can start immediately). Absorbs `.todo/230` (`subtypep` on class
metaobjects) — that item is PROMOTED from optional to required here: mito's
`contains-class-or-subclasses` (src/core/util.lisp:101-111) does
`(typep class 'class)` and `(subtypep target-class class)` on metaobjects.

## What mito actually needs (grep of mito-20260101-git, exact sites)

The existing protocol (`macro/mop-protocol.lisp`, `%ensure-class-with-metaclass`,
seeded `%obj-ref` index contract) covers postmodern's DAO shape. Mito's
`table-class`/`dao-table-class`/`dao-table-mixin` add, in rough order of pain:

1. **`ensure-class-using-class` routing** — dao/table.lisp:124 defines
   `c2mop:ensure-class-using-class :around` that INJECTS `dao-class` into
   `:direct-superclasses`. The current driver never calls e-c-u-c; route
   `%ensure-class-with-metaclass` through it (default method = today's body)
   so user :arounds participate. Note the :around runs on a class INSTANCE for
   redefinition and on... check AMOP: e-c-u-c dispatches on the EXISTING class
   (nil for a new one — mito's method specializes `(class dao-table-class)`,
   i.e. it fires on REdefinition; first definition goes through
   `initialize-instance`, item 2). Get this dispatch split right first.
2. **User `initialize-instance :around` on metaclass instantiation** —
   class/table.lisp:115, dao/table.lisp:110, dao/mixin.lisp:182 munge
   `:direct-superclasses` in initargs (push `(find-class 'dao-class)`
   metaobjects) before `call-next-method`. `%mop-make-instance` must run these
   around the seeded initialization.
3. **Class REDEFINITION (`reinitialize-instance` path)** — class/table.lisp:125,
   dao/table.lisp:118, dao/mixin.lisp:189 (+ migration/table.lisp:422 :after).
   Evaluating `deftable`/`defclass` for an EXISTING name must take the
   reinitialize path (update slots, re-finalize) instead of silently making a
   fresh metaobject. Scope decision: interpreter = real redefinition; the
   compile paths see a static program where a same-name redefinition is
   resolvable at compile time — if full re-layout is disproportionate there,
   record the divergence + trigger in `.kb/clos.md` (e.g. "last definition
   wins, methods keyed by name see the final layout").
4. **Slot-definition contract additions** — mito reads
   `c2mop:slot-definition-initfunction` (4 sites: dao.lisp, conversion) —
   the seeded slot contract (name, initargs, initform, type, readers) has no
   INITFUNCTION; append index 5 (append-only contract) holding a thunk of the
   initform. Also `closer-mop.lisp` shim lacks the `slot-definition-readers`
   and `slot-definition-initfunction` ACCESSORS and `class-direct-slots` /
   `class-direct-subclasses` (dao.lisp uses direct-subclasses once, for
   deftable-view resolution). `standard-direct-slot-definition` /
   `standard-effective-slot-definition` are subclassed with extra slots
   (mito's column classes add ghost/col-type/references...) — already the
   postmodern shape, but verify initargs of the EXTRA slots survive
   `compute-effective-slot-definition` (mito overrides
   `direct-slot-definition-class`/`effective-slot-definition-class`/
   `compute-effective-slot-definition` — 4+4+2 sites).
5. **`typep`/`subtypep` on metaobjects** (= old `.todo/230`): `(typep x
   'class)`, `(typep c 'table-class)` where x is a metaobject; `(subtypep
   class-a class-b)` on metaobjects. The ancestor walk exists in ClosRegistry;
   route the type predicates through it when arguments are metaobjects, on
   all four backends (`%class-meta-table%` on compile paths).
6. **Unknown class options as metaclass initargs** — `deftable` passes
   `(:conc-name ...)`, mito user code passes `(:table-name ...)`
   `(:primary-key ...)` etc.; Phase B already canonicalizes unknown options
   to initargs (`.kb/clos.md`) — verify list-tail canonicalization matches
   what mito's `initialize-instance` expects (it getf's them).

## Suggested split if the session runs long

Items 1+2+6 (definition-time hooks) are the load gate for mito-core's own
sources; 3 (redefinition) is the gate for RE-evaluating a deftable and for
mito-migration; 4+5 are mechanical. If needed, land 1/2/4/5/6 and spin 3 off
into a follow-up todo rather than shipping a half-wired redefinition.

## Acceptance

- A synthetic metaclass test reproducing mito's exact shape WITHOUT mito:
  e-c-u-c :around superclass injection + initialize-instance :around initarg
  munging + custom direct/effective slot classes with an extra slot +
  initfunction readback + redefinition of the same class name — identical
  behavior on all four backends (ci-spec.yaml case).
- `typep`/`subtypep` metaobject cases pinned (the old `.todo/230` acceptance).
- `.kb/clos.md` updated in the same pass: the new protocol pieces, the index
  contract addition (slot 5 = initfunction), and the redefinition scope with
  its reason + re-evaluation trigger.
