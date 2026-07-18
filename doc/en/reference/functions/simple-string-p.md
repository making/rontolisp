# simple-string-p

`(simple-string-p object)`

Returns true when `object` is a string. Lite: every rontolisp string answers true (there is no separate simple-string representation), so the portable "coerce unless `simple-string-p`" idiom keeps the string unchanged instead of copying.

```lisp
(simple-string-p "abc") ; => t
```

```lisp
(simple-string-p 42) ; => nil
```
