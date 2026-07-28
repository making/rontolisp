# Make s-sql (Postmodern's SQL DSL) load and run

Goal: `(ql:quickload "s-sql")` loads the verbatim upstream sources
(`~/.rontolisp/quicklisp/software/postmodern-20260101-git/s-sql/`, 3 files,
~2930 lines) and `(sql (:select '* :from 'foo :where (:= 'id 1)))` produces the
right string on all backends (interpreter / JVM / both WASM -- s-sql itself
needs no sockets, so unlike cl-postgres it should work on Preview 1 too).
`:depends-on ("cl-postgres" "alexandria")`, both already loadable; the `.asd`
is plain data, so no `AsdOverrides` entry is needed.

First layer of the postmodern stack (`.todo/115` "Out of scope" follow-up).
Blocks `.todo/202-postmodern-non-mop-milestone.md`.

## Confirmed gaps (probed 2026-07-28, interpreter)

- **`~^` inside `~{...~}`** -- `(format nil "~{~a~^, ~}" '(1 2 3))` returns the
  garbled `"~{(1 2 3)~^, ~}"` instead of `"1, 2, 3"`. s-sql uses this shape in
  at least 4 control strings (`s-sql.lisp:289,330,397,1586`) plus
  `"~:['{}'~;ARRAY[~:*~{~A~^, ~}]~]"` (394, also needs `~:*` INSIDE `~[`).
  `.todo/001-advanced-format-directives.md` tracks `~^` generally; this is its
  first real library consumer.
- **`with-output-to-string` binding `*standard-output*` as the target var** --
  `(with-output-to-string (*standard-output*) (princ "x"))` prints to real
  stdout and returns `""`. s-sql has 7 `with-output-to-string` sites, several
  using exactly this shape (`to-sql-name`, `sql-escape-string`,
  `sql-template`). Needs the special-variable rebinding to reach `princ`/
  `write-char`/`format t` on every backend (ties into
  `.todo/149-standard-stream-specials.md`).
- **`readtable-case` is undefined** -- `from-sql-name` (s-sql.lisp:240) calls
  `(readtable-case *readtable*)`. A constant `:upcase` stub is honest here
  (rontolisp's reader upcases; see `.todo/041-readtable-and-printing-control.md`).
- **`(intern x (find-package :keyword))` is a hard error** -- s-sql.lisp:243-247
  interns keywords via a NON-LITERAL package argument. Runtime package
  designators are `.todo/198-runtime-package-and-symbol-ops.md`; the minimal
  fix is accepting any designator that names the keyword package.
- **Parameterized `deftype` is a parsed no-op with an unresolvable name** --
  s-sql defines 11 types, of which `numeric (&optional precision/scale scale)`
  and `varchar (length)` are parameterized, and `db-null` is `'(eql :null)`
  (an `eql` type specifier). These names flow into `sql-type-name` dispatch
  and `dissect-type`; audit which ones must actually resolve via `typep`
  (mostly they are dispatched as SYMBOLS, not used as type specifiers, so the
  real requirement may be small -- verify before building general
  parameterized deftype).

## Confirmed working (same probe)

`:for`/`:=`/`:then` keyword-spelled loop clauses, parallel `for`+`then`,
pipe-escaped symbols `|a b|` and keywords `:|a b|` (needed for the operator
keywords `:|\||`, `:|@>|`, `:||`, ...), `~:@(~a~)`, `~:[~;~]`,
nested backquote with `,,` (full CLtL2 port), `multiple-value-prog1`,
`values-list`, `nth-value`, adjustable fill-pointer strings +
`vector-push-extend`, `macrolet` as a `setf`/`push` place, `defmethod` on
`defstruct` types, toplevel `(let (...) (defun ...))` closures (interpreter;
verify the compile path).

## Scale / dispatch risks to watch

- `expand-sql-op` carries ~230 methods on ONE generic function, all
  `(eql :keyword)`-specialized, plus a `(op t)` default. eql dispatch on
  keywords is supported, but nothing has run it at this scale -- watch the
  JVM 64KB method / constant-pool ceilings and the wasm function-body-size
  wall (`.kb/jvm-method-size-limits.md`, `.kb/wasm-function-body-size.md`);
  this may need the same chunked-table lowering as `%typep-runtime`.
- `sql-type-name` mixes 14 `(eql 'symbol)` methods with a `(lisp-type symbol)`
  class method -- eql-beats-class specificity must hold.
- `to-s-sql-string` needs `integer`/`float`/`double-float`/`ratio` as
  specializable classes with correct specificity, and returns TWO values
  consumed by `multiple-value-bind` at every call site.
- `sql-error` is `(define-condition sql-error (simple-error) ...)` used with
  `:format-control`/`:format-arguments` -- verify the seeded condition
  hierarchy supports that pair.
- `enable-s-sql-syntax` calls `set-dispatch-macro-character` (`#Q`); it is
  opt-in and never called at load time -- it only needs to not break loading
  (a defun referencing an undefined function is now a compile warning +
  call-time error, which is enough).

## Verification

Per the CLAUDE.md ladder: interpreter -> JVM -> WASM integration tests, a
`ci-spec.yaml` case exercising `(sql ...)` end-to-end, docs page for the
library, and the native-image E2E run.
