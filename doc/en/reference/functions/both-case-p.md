# both-case-p

`(both-case-p character)`

Returns true if the character is a cased letter (it has both an upper- and a lowercase form): `lower-case-p` or `upper-case-p`.

```lisp
(both-case-p #\a) ; => t
```

```lisp
(both-case-p #\5) ; => nil
```
