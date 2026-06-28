# remhash

`(remhash key table)`

Removes the entry for `key` from `table`. Returns `t` if an entry was present and removed, or `nil` if the key was not found. Keys are matched structurally, the same way `gethash` looks them up.

```lisp
(let ((h (make-hash-table)))
  (setf (gethash 'a h) 1)
  (remhash 'a h)) ; => t
```
