# both-case-p

`(both-case-p character)`

Returns true if the character is a cased letter (it has both an upper- and a lowercase form): `lower-case-p` or `upper-case-p`. A non-character signals a `type-error` naming the operator.

```lisp
(both-case-p #\a) ; => T
```

```lisp
(both-case-p #\5) ; => NIL
```
