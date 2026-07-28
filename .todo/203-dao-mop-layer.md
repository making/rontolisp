# Milestone: postmodern DAO layer (:postmodern-use-mop) -- the MOP question

Goal: the "highest level" postmodern API -- `table.lisp` (955 lines: DAO
classes, `get-dao`/`select-dao`/`insert-dao`/`update-dao`/`upsert-dao`/
`save-dao`, `dao-table-definition`) and the DAO halves of `deftable.lisp` and
`json-encoder.lisp` -- on all TCP-capable backends. This is the layer the
whole postmodern effort exists for; without it `.todo/202` delivers query/
transaction sugar but no object mapping.

Depends on `.todo/202-postmodern-non-mop-milestone.md` landing first.

## Why this is a design problem, not a feature list

`.kb/clos.md` declares MOP "permanently out (contradicts the static compile
model + --optimize)". Upstream table.lisp requires, verbatim:

- `(defclass dao-class (standard-class) ...)` -- a user METACLASS -- plus
  `direct-column-slot`/`effective-column-slot` subclassing
  `standard-direct-slot-definition`/`standard-effective-slot-definition`.
- Specialized MOP protocol GFs: `validate-superclass`,
  `direct-slot-definition-class`, `effective-slot-definition-class`,
  `compute-effective-slot-definition` (communicating via a special binding),
  `finalize-inheritance :after`, `shared-initialize :before/:after` on the
  CLASS and on SLOT-DEFINITION metaobjects.
- MOP accessors: `class-slots`, `slot-definition-name`,
  `class-direct-superclasses`, `class-finalized-p`, `class-name`, `class-of`
  returning a real class OBJECT, real `find-class`, `closer-mop:classp`,
  `allocate-instance` + direct `initialize-instance` call.
- **Runtime codegen**: `build-dao-methods` does
  `(funcall (compile nil (lambda () ,code)))` where `code` defines ~8
  `defmethod`s whose specializers are the LIVE CLASS METAOBJECT and an
  `(eql (class-name class))` specializer whose form is EVALUATED.
- A `closer-common-lisp` package (the `postmodern` defpackage `:use`s it
  under the MOP feature); today's closer-mop shim is 4 functions over
  2-element lists.
- NOT needed (bound the work): `slot-value-using-class`, `compute-slots`,
  `ensure-class`, funcallable instances, class redefinition.

## The essential observation for a static implementation

Every input to this machinery is STATIC in real programs: DAO classes are
top-level `(defclass foo () (...) (:metaclass dao-class))` forms; class
options and slot options are literals; `finalize-inheritance` fires at
load/first-use; the `%eval`'d method bodies depend only on the class
definition. So there are two viable strategies -- decide deliberately and
write the reason into `.kb/clos.md` as the re-evaluation trigger the working
principles require:

1. **Static MOP-subset execution at expansion time.** Teach the shared
   expander to RUN the class-definition-protocol (the upstream table.lisp
   code itself, or a faithful port) during macro/quickload expansion:
   `:metaclass dao-class` triggers compile-time computation of columns/keys/
   sql-names, and `build-dao-methods`' generated `defmethod`s are spliced as
   ordinary static method definitions (class-object specializer -> the class
   name; evaluated eql specializer -> fold `(class-name class)` statically).
   `compile nil` at expansion time is just "expand and splice". Fits
   `--optimize` and all four backends; diverges from upstream only for
   programs that build DAO classes from runtime data (accept + document).
   Likely requires running the REAL table.lisp under an expansion-time
   mini-CLOS, or a Tier-3/4 rewrite of table.lisp -- weigh "verbatim
   upstream sources" (the cl-postgres bar, `.todo/115`) against feasibility;
   a rewrite shim contradicts `.todo/147`'s policy direction, so if a shim is
   chosen, record why.
2. **Real runtime MOP on the interpreter + static lowering on compilers.**
   Larger, and creates exactly the backend divergence the working principles
   warn about; only pick with a written why.

Prerequisite hard features either way: real class objects behind
`find-class`/`class-of` (today: nil / tag symbol), `allocate-instance` with
unbound slots (LANDED with `.todo/199`: a slot with no `:initform` starts
unbound, see `.kb/clos.md`), method definition ordered
after class finalization, and `defmethod ... :around ((class (eql 'name)))`
from `define-dao-finalization`.

## Also in this milestone

- Turn `:postmodern-use-mop` ON. The replacement `postmodern-deps.asd` keeps
  upstream's `:if-feature` / `(:feature ...)` clauses verbatim exactly so this
  is a feature flip and not a re-edit -- `table.lisp` rejoins the build,
  `deftable` depends on it, and postmodern's own `defpackage` switches its
  `:use` to `:closer-common-lisp` on its own. What is missing is the MECHANISM
  to make a reader feature true for one system's component files, which is
  scoped in `.todo/204` (§3) alongside the same problem for
  `:postmodern-thread-safe`. Then add the `closer-mop` dependency and widen
  the closer-mop shim accordingly. Pinned today by
  `AsdfSystemsTest.thePostmodernMopBuildIsAFeatureFlip`.
- `save-dao/transaction`, `do-select-dao`, `with-column-writers`,
  `dao-row-reader-with-body`; `handler-case` on `unique-violation` /
  `columns-error` / `unbound-slot`.
- E2E: extend the postmodern E2E with a DAO round-trip (deftable + create +
  insert-dao + get-dao + upsert-dao returning `(values dao inserted-p)`),
  byte-identical across the three backends.
- Docs: DAO pages (en+ja); document whatever static restrictions strategy 1
  imposes.
