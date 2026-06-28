# typecase

`(typecase x (integer body...) (string body...) (t default...))`

Evaluates `x` once and selects the first clause whose type name `x` satisfies, evaluating that clause's body and returning its last value. The supported type names are `integer`, `float`, `number`, `rational`, `string`, `symbol`, `keyword`, `cons`, `list`, `null`, and `atom`, plus a final `t`/`otherwise` default clause. If no clause matches and there is no default, `typecase` returns nil.

```lisp
(let ((x "hi")) (typecase x (integer 'int) (string 'str) (t 'other))) ; => str
```
