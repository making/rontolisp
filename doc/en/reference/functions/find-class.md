# find-class

`(find-class symbol &optional (errorp t) environment)`

Returns the class metaobject named by `symbol` -- a `standard-class` instance whose slots the `closer-mop` readers (`class-name`, `class-slots`, `slot-definition-name`, ...) consume. The answer is memoized, so two calls for the same class return the same (`eq`) object. When no class is named `symbol`, an error is signaled unless `errorp` is `nil`, in which case `nil` is returned; `environment` is ignored. The known classes are every `defclass` / `define-condition` in the program plus the built-in condition hierarchy. On the compiled backends the class set is fixed at compile time; classes built from runtime data do not exist.

```lisp
(defclass point () ((x :initarg :x)))
(list (eq (find-class 'point) (find-class 'point))
      (find-class 'no-such-class nil)) ; => (T NIL)
```
