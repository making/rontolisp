# list

`(list obj ...)`

Returns a newly allocated list of its arguments, in order. With no arguments it answers the empty list.

```scheme
(list 1 2 3) ; => (1 2 3)
(list 'a "b" #\c 1.5) ; => (a "b" #\c 1.5)
(list) ; => ()
```
