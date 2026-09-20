# copy-structure

`(copy-structure structure)`

Returns a fresh instance of `structure`'s type (CLHS 18.3): the slots are freshly allocated, but their VALUES are shared with the original -- a shallow copy, so mutating a slot of the copy never touches the source and vice versa. It is the generic counterpart of the `copy-<name>` copier [`defstruct`](../special-forms/defstruct.md) generates for one known type: that copier's tag is known at compile time and goes straight through the `%obj-new` instance constructor, while `copy-structure` reads its argument's own layout at run time, since the type is not known until then.

```lisp
(defstruct point x y)
(let* ((p (make-point :x 1 :y 2))
       (q (copy-structure p)))
  (list (eq p q) (equal p q) (point-x q))) ; => (NIL T 1)
```

Mutating the copy leaves the original untouched, since only the slot storage is fresh:

```lisp
(defstruct point x y)
(let* ((p (make-point :x 1 :y 2))
       (q (copy-structure p)))
  (setf (point-x q) 99)
  (list (point-x p) (point-x q))) ; => (1 99)
```
