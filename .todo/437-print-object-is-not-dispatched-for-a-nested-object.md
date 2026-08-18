# 437. `print-object` is not dispatched for a NESTED object

Difficulty: Medium

Child of `.todo/436` (read it first: origin, ordering, shared hazards).
Wave 1, and a prerequisite for `.todo/438` -- it turns a cyclic-key blowup from
an unreadable stack trace into a finite print.

## The defect

A `print-object` method is honoured only when the object is printed ALONE. As an
element of a list or a vector it falls back to the built-in renderer, and `~S`
behaves the same.

```lisp
(defclass c () ((x :initarg :x)))
(defmethod print-object ((o c) s) (format s "#<C custom>"))
(let ((i (make-instance 'c :x 1)))
  (print i)                  ; => #<C custom>    correct
  (print (list i))           ; => (#<C :X 1>)    want (#<C custom>)
  (format t "~S~%" (list i)) ; => (#<C :X 1>)    want (#<C custom>)
  (print (vector i)))        ; => #(#<C :X 1>)   want #(#<C custom>)
```

A library that defines `print-object` to keep its objects readable -- or merely
FINITE, which is upstream ASDF's reason: its `component` method prints
`#<SYSTEM "x">` instead of walking a cyclic parent/children graph -- only gets
it when the object happens to be printed at the top level.

## The fix

Every value-printing path that can reach an instance must consult the method:
`LispInstance.print`/`render`, `LispCons.print`, the vector/array printers,
`format`'s `~S`/`~A`, `write`/`print`/`prin1`/`princ`, and the pprint dispatch.
All four backends.

Read `.kb/clos.md` (where the dispatch happens today), `.kb/pretty-printer.md`
(printer entry points and the control variables), `.kb/instance-syntax.md`
(`#<...>` / `#S(...)` rendering and its per-backend shapes).

## Watch

- **An instance with NO `print-object` method must print byte-identically to
  today.** ci-spec, `DocExamplesTest` and the per-backend tests depend on the
  built-in rendering in bulk.
- Do not touch hash-table key computation. `LispHashTable` keys by `print()`, so
  this change can make a cyclic key accidentally terminate -- that is
  `.todo/438`'s problem, and the key's meaning must not move here.
- `*print-circle*` is out of scope.

## Acceptance

The four lines above all print `#<C custom>`; existing output tests green on all
four backends; a ci-spec case (`print-object-nested-437`).
