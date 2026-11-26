# delete-package

`(delete-package package-designator)`

Removes a package created by [`make-package`](make-package.md) -- the
registration and its nicknames -- and returns `t`. Deleting a read/compile-time
package (a built-in or a [`defpackage`](../special-forms/defpackage.md) product)
signals a catchable `package-error`, because every backend resolved against it;
so does a designator that names no package.

```lisp
(make-package :doc-dp)
(delete-package :doc-dp) ; => T
(find-package :doc-dp) ; => NIL
```
