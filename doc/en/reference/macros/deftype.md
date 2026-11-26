# deftype

`(deftype name lambda-list body...)`

Defines `name` as a type for later [`typep`](typep.md)/[`typecase`](typecase.md) tests and returns `nil`. The body returns the type specifier the name stands for (a `(satisfies predicate)` body calls the named predicate; the name may expand to another type name). For a parameterized `deftype`, a compound specifier `(name args...)` is what the body returns with the lambda list bound to `args`, an `&optional` or `&key` parameter without an init form defaulting to `*`; the bare name of one whose parameters are all optional means `(name)`.

```lisp
(deftype my-even () '(satisfies evenp))
(list (typep 4 'my-even) (typep 3 'my-even)) ; => (T NIL)
```

```lisp
(deftype vec-of (n) `(simple-vector ,n))
(list (typep (vector 1 2) '(vec-of 2)) (typep (vector 1 2 3) '(vec-of 2))) ; => (T NIL)
```
