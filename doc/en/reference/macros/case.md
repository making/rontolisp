# case

`(case key (k1 body...) ((k2 k3) body...) (otherwise body...))`

Evaluates `key` once and compares it with `eql` against each clause's unevaluated key(s), running the body of the first match and returning its last value. A clause whose key is a list matches if `key` is `eql` to any element of that list. A final `t` or `otherwise` clause is the default; if nothing matches and there is no default, `case` returns nil.

```lisp
(let ((x 2)) (case x (1 'one) ((2 3) 'two-or-three) (otherwise 'other))) ; => two-or-three
```
