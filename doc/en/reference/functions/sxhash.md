# sxhash

`(sxhash object)`

Returns a non-negative integer hash of the object, hashing integers, characters, strings, symbols, and conses by structural content (anything else hashes to 0). Values are stable within a run but NOT specified across backends.

```lisp
(= (sxhash "ab") (sxhash "ab")) ; => T
```
