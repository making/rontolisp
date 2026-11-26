# 251. mito `deftable` accessors are never defined, and relational `:col-type <class>` needs runtime method construction

Difficulty: 高 (both items land in the same place: a metaclass may inject
method-shaped DATA that rontolisp records but never materialises. The second
item needs a MOP surface `.kb/clos.md` currently lists as out of scope.)

Found while closing `.todo/249` (the mito milestone `.todo/238`). Neither blocks
the migration workflow -- both are DAO-surface gaps -- so they were recorded
rather than fixed. All three items were checked against SBCL 2.2.9 loading the
same cached sources; SBCL does all of them.

## 1. `deftable`'s conc-name accessors do not exist

```lisp
(mito.core:deftable acct () ((code :col-type :text)))
(acct-code (make-instance 'acct :code "x"))
;; rontolisp => The function ACCT-CODE is undefined   (slot-value works)
;; SBCL      => "x"
```

mito's `src/core/dao/column.lisp:58-61` injects `:readers`/`:writers` into the
canonicalized slot spec from a slot-definition `initialize-instance :around`.
rontolisp records those as metaobject DATA; `expandDefclass` generates reader
and writer methods only from the ORIGINAL `defclass` form, which carried none.
So every mito DAO is slot-value-only.

## 2. `*package*` reads the DEFINING file's package, not the load-time one

The same accessor site interns with an explicit `*package*`:

```lisp
(intern (format nil "~:@(~A~A~)" *conc-name* name) *package*)
```

and rontolisp answers `MITO.DAO.COLUMN` there -- the package of the file that
CONTAINS the form -- where CL answers the caller's `*package*`. Minimal repro:
a helper file in package `HLP2` defining `(defun pkgname () (package-name
*package*))`, called from a file in package `PROBEG`, answers `"HLP2"` in
rontolisp and `"PROBEG"` in SBCL. (1-argument `intern` itself is correct; it is
the `*package*` VARIABLE read that is lexical here.)

Consequence for item 1: even once accessors are materialised they would land in
the wrong package. Fix this one first -- it is the smaller of the two and
`.kb/packages.md` / `.kb/symbol-runtime-api.md` own the topic.

## 3. Slot names lose their package in the metaclass driver

`LispMacroExpander.java:8946` (`ensureClassWithMetaclassCall`) emits
`quoteOf(slot.baseName())` -- the MEMBER name -- where the source spelled
`PROBE::ID`, and `ClosRegistry.java:1248` does the same for the materialised
effective-slot views. mito's `find-slot-by-name` (`class/table.lisp:240-246`)
tests `eq` on `c2mop:slot-definition-name`, so it never matches a
caller-package symbol, `table-column-references-column` answers nil, and the
`table-column-info :around` SILENTLY skips inheriting the referenced column's
type. No error -- just wrong DDL:

```lisp
(mito.core:deftable acct () ((code :col-type (:varchar 36) :primary-key t)))
(mito.core:deftable ord  () ((acct-code :col-type :text :references (acct code))))
;; SBCL:      acct_code VARCHAR(36) NOT NULL
;; rontolisp: acct_code TEXT NOT NULL      <- wrong, no error
```

Spelling `:references (acct cl-user::code)` makes rontolisp produce
`VARCHAR(36)`, which is the proof. Minimal fix: emit `slot.name()` at both
sites and align the `eq`-by-name consumers on the metaobject side
(`%mop-find-slot-definition`, `compute-effective-slot-definition`'s `:name`,
`%mop-compute-slots` in `macro/mop-protocol.lisp`) while
`ClosRegistry.slotPositions` / the layout keep keying on `baseName`. For a
program with no user packages `slot.name() == slot.baseName()`, so output stays
byte-identical.

## 4. Relational `:col-type <class-name>` — the README's own syntax

```lisp
(mito:deftable tweet () ((status :col-type :text) (user :col-type user)))
;; rontolisp => The function MITO.DAO.MIXIN::ENSURE-GENERIC-FUNCTION is undefined
;; SBCL      => works: a ghost slot USER plus a real USER-ID BIGINT column
```

`src/core/dao/mixin.lisp:108-110` (`make-relational-reader-method`) calls
`ensure-generic-function` + `add-method` + `(make-instance 'standard-method ...)`
from an `initialize-instance`/`reinitialize-instance :around` on
`dao-table-mixin`, so it blocks first definition AND redefinition. None of
`ensure-generic-function`, `find-method`, `add-method`, `remove-method` or the
`standard-method` class exists; `.kb/clos.md` lists runtime method construction
as explicitly OUT of scope, and the Phase-C `MopEvalCapture` interception covers
only postmodern's `(funcall (compile nil ...))` idiom, not mito's raw-MOP
spelling.

Sub-bug worth fixing regardless of the scope decision:
`(make-instance 'standard-method ...)` on an unregistered class throws a RAW
`java.lang.IllegalArgumentException` from `LispMacroExpander.java:9389` that
`handler-case` cannot catch and that aborts the program -- the `coldBranchOk`
branch two lines above already returns a catchable `(error "...")`, and the
interpreter path should do the same.

## Acceptance

- `(acct-code obj)` works and the symbol is interned in the CALLER's package.
- The `:references` DDL inherits the referenced column's type with the plain
  spelling, on all four backends.
- A decision recorded in `.kb/clos.md` for item 4: either the runtime
  method-construction trio lands (with its own re-evaluation trigger) or mito's
  relational `:col-type <class>` is documented as unsupported with the reason.
