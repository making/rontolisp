# macroexpand-1

`(macroexpand-1 form)`

Expands a macro call once and answers the expansion as data: the mangled data
itself, so `=` against a quoted form holds and printing spells the oracle's
lowercase. A form whose head names no macro answers itself. The head resolves
in `*ns*` when the call runs, like the oracle: a bare name through that namespace's
own macros and refers, an alias-qualified one through its aliases, a core macro by its
bare name unless the namespace defines, refers or excludes that name. Also names a
function value.

A core macro expands to the oracle's form: `let` to `let*` over the destructured
bindings, `fn` to `fn*`, `case` to `case*` over its hashed map, `doseq` and `for` to
their chunked walks, and so on for the macros [Deviations](../deviations.md) lists; any
other core macro (`lazy-seq`, `defmacro`, ...) answers the form itself. Every macro
call goes through the macro's function, which its var holds and takes the call, the
environment and the call's arguments, like the oracle's: `(#'when '(when a b) nil 'a 'b)`
answers `(if a (do b))`.

```clojure
(defmacro doc-unless2 [c t] (list 'if c nil t))
(println (macroexpand-1 '(doc-unless2 true 1))) ; (if true nil 1)
(println (= (macroexpand-1 '(doc-unless2 true 1)) '(if true nil 1))) ; true
(println (macroexpand-1 '(when a b))) ; (if a (do b))
(println (macroexpand-1 '(let [x 1] x))) ; (let* [x 1] x)
```
