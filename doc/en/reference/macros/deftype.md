# deftype

`(deftype name lambda-list body...)`

A **zero-parameter** `deftype` whose body is a literal (quoted) type specifier is registered, so the defined name resolves as a type in later [`typep`](typep.md)/[`typecase`](typecase.md) tests (a `(satisfies predicate)` body calls the named predicate; the name may itself expand to another type name). A **parameterized** or otherwise computed `deftype` stays a parsed no-op returning `nil` — there is no per-call expansion, so its name is not resolvable, which supports the common library shape where such a name only appears inside (equally no-op) `declaim`/`declare` declarations.

```lisp
(deftype my-even () '(satisfies evenp))
(list (typep 4 'my-even) (typep 3 'my-even)) ; => (T NIL)
```

```lisp
(deftype array-index (&optional (length 1000)) `(integer 0 (,length))) ; => NIL
```
