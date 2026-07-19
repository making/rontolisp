# typecase

`(typecase x (integer body...) (string body...) (t default...))`

Evaluates `x` once and selects the first clause whose type specifier `x` satisfies, evaluating that clause's body and returning its last value. The supported type names are `integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, `atom`, `character`, `hash-table`, and `boolean`, plus a final `t`/`otherwise` default clause. Compound specifiers also work: `(or ...)`, `(and ...)`, `(not ...)`, `(member item...)`, `(eql object)`, `(satisfies function)`, and ranged numeric types like `(integer 0 9)` (see `check-type` for the details). A zero-parameter user [`deftype`](deftype.md) name resolves too. If no clause matches and there is no default, `typecase` returns nil.

```lisp
(let ((x "hi")) (typecase x (integer 'int) (string 'str) (t 'other))) ; => str
```

```lisp
(let ((n 5)) (typecase n ((integer 0 9) 'digit) (integer 'int))) ; => digit
```
