# catch

`(catch tag body...)`

Establishes a **dynamic** exit point named by `tag` and returns the value of the last `body` form -- or, if a [`throw`](throw.md) to an `eq` tag fires anywhere in the body's dynamic extent, the thrown value. The tag is an ordinary runtime value evaluated once on entry (usually a quoted symbol), so unlike [`block`](../macros/block.md)/`return-from` the thrower does not need the catcher in lexical scope: it only has to run while the `catch` is active. The innermost active `catch` with a matching tag wins; a non-matching one lets the exit pass through.

`catch`/`throw` work on **every backend** except `--no-gc` (a compile error there). On the wasm-GC backends (Preview 1 and `--component`) they compile through the WebAssembly exception-handling proposal, so running a program that uses them needs wasmtime 37+ with `-W exceptions=y`, exactly like [`unwind-protect`](unwind-protect.md) and `handler-case`.

A `throw` is a non-local exit, not a condition: it unwinds **through** a `handler-case` without being caught, while every intervening `unwind-protect` cleanup does run.

```lisp
(catch 'done (throw 'done :thrown) :not-reached) ; => :THROWN
```

The exit crosses function boundaries, which is what makes it useful for bailing out of a callback:

```lisp
(defun first-even (xs)
  (catch 'found
    (map nil (lambda (x) (if (evenp x) (throw 'found x))) xs)
    :none))
(list (first-even '(1 3 4 5)) (first-even '(1 3 5))) ; => (4 :NONE)
```

Tags are compared with `eq`, so a freshly consed tag matches only itself:

```lisp
(catch 'outer (catch (list 1) (throw 'outer :to-outer))) ; => :TO-OUTER
```
