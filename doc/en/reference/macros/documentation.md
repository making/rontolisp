# documentation

`(documentation name doc-type)` / `(setf (documentation name doc-type) docstring)`

Lite: docstrings are not stored anywhere, so reading returns `nil` and the `setf` form evaluates to the docstring while discarding it. Accepted so libraries that attach documentation at load time (`(setf (documentation 'f 'function) "...")`) load unchanged.

```lisp
(defun greet () "hi")
(setf (documentation 'greet 'function) "Says hi.") ; => "Says hi."
(documentation 'greet 'function) ; => nil
```
