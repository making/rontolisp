# append

`(append list ...)`

Returns a list of the elements of the first lists followed by the last argument. Every argument but the last is copied; the last is shared, and may be any object, which then becomes the result's tail. `(append)` is `()`.

```scheme
(append '(1 2) '(3) '(4 5)) ; => (1 2 3 4 5)
(append '(1) 2) ; => (1 . 2)
(append) ; => ()
```
