# CLOS gaps postmodern needs WITHOUT the MOP build

Goal: the CLOS-subset extensions (`.kb/clos.md` -- read it first) that the
non-MOP postmodern build uses. Everything here fits the existing static
compile-time model (all class/method definitions are top-level literal forms
in the library sources); none of it requires the MOP
(`.todo/203-dao-mop-layer.md` is the separate MOP story).

Blocks `.todo/202-postmodern-non-mop-milestone.md`.

## Confirmed hard errors today (probed 2026-07-28)

- **Inherited-slot shadowing**: `(defclass sub (base) ((a :initform 2 ...)))`
  dies with "slot A is already defined in a superclass".
  `postmodern/transaction.lisp` needs it: `savepoint-handle` re-defines the
  inherited `open-p` and `connection` slots with new `:initform`/`:accessor`/
  `:reader` names. CLHS semantics: the subclass slot SPEC shadows (initform,
  accessors add up); the slot storage stays one slot. The static layout can
  support this -- keep the superclass index, override the initform, add the
  extra accessors.
- **Erroring `:initform`**: `transaction-handle`'s `name` slot has
  `:initform (error "...")` -- verify an initform that signals works (it
  should, initforms are evaluated at make-instance time) and that
  `make-instance` with the initarg supplied does NOT evaluate it.

## Absent features with concrete sites

- **`change-class`** -- `postmodern/connect.lisp:37,40` mutates a live
  `database-connection` into `pooled-database-connection` (a subclass adding
  one slot, with a `:pool-type` initarg passed to `change-class`). Both
  classes are statically known, single site pattern; a static-model
  implementation (copy slots into the wider layout, in place if
  representation allows) is feasible. Also needed: `defclass` subclassing a
  class DEFINED IN ANOTHER LIBRARY (cl-postgres's `database-connection`) --
  should already work via the global ClosRegistry, verify across the
  quickload/compile splice boundary.
- **Real slot unboundness** -- `slot-boundp` / `slot-makunbound` are lite
  stubs (always bound, nil default). postmodern has 22 `slot-boundp` sites
  and a `handler-case` on the standard **`unbound-slot`** condition
  (generated `upsert-dao`; also the DAO layer leans on unboundness for
  "column not fetched"). Needs a distinguished unbound marker per slot,
  `slot-makunbound`, and `unbound-slot` signaled from `slot-value` --
  seeded-condition-hierarchy work, see `.kb/error-handling.md`.
- **`print-object`** -- one method, on the STRUCT `parser`
  (`execute-file.lisp:15`), with `print-unreadable-object :type t :identity
  t`. Requires: a `print-object` GF the printer consults, defmethod on
  defstruct types (dispatch itself confirmed working), and `:identity`
  output. `.todo/171-struct-instance-print-and-read-syntax.md` is adjacent.
- **`with-accessors`** -- `json-encoder.lisp:110,285` (one inside a condition
  `:report`). Pure macro over `symbol-macrolet`-style rebinding; can expand
  to direct accessor calls without symbol-macrolet since the bodies only read.
- **`with-slots` on struct instances** -- `execute-file.lisp:17`. Today
  `with-slots` targets CLOS instances; extend to defstruct.
- **`defgeneric` with `&optional`** -- `encode-json (object &optional
  stream)` with per-method defaults `(stream *json-output*)`; verify the
  congruence rules accept it on all backends.
- **`define-condition` extras** -- `:default-initargs` on a condition
  (`unencodable-value-error`, also inheriting `type-error` with
  `type-error-datum` readers) and `:report` LAMBDAS (several sites; verify --
  report strings vs lambdas).
- **`:documentation` slot option on `defgeneric`/methods** -- already
  accepted/ignored, nothing to do (listed to close the audit).

## Explicitly NOT needed by postmodern (do not build speculatively)

`:allocation :class`, slot `:writer`, `:method-combination`, multiple
inheritance (no site anywhere in s-sql/postmodern), `symbol-macrolet`
(never used!), `define-compiler-macro`, `declaim`.
