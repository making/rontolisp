# do-all-symbols

`(do-all-symbols (var [result]) body...)`

Evaluates the body once per distinct symbol accessible in any registered
package, with `var` bound to the symbol, then evaluates `result` with `var`
bound to nil and returns its value (nil when no result form is given). Each
symbol is visited once and spelled the way code spells it, exactly the universe
[`find-all-symbols`](../functions/find-all-symbols.md) searches. A `return` in
the body exits the whole form, skipping the result, like the other iteration
macros.

```lisp
(let ((n 0))
  (do-all-symbols (s n) (when (eq s 'car) (return (incf n))))) ; => 1
```
