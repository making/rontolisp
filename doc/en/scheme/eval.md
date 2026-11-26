# eval

`(eval datum env)` evaluates a datum at run time, on every backend. Every environment
specifier is the one global environment: `(interaction-environment)`,
`(scheme-report-environment 5)`, `(environment '(scheme base) ...)` -- its import sets
are checked against [the libraries](libraries.md) -- and MIT Scheme's
`user-initial-environment`
and `system-global-environment` all name it, and the argument may be left out (not under
[`--scheme-standard r7rs`](standards.md)). It holds
the program's variables (including what `eval` itself defined), the program's
procedures, and the built-in procedures, in that order. A `define` inside `eval` is a program global, visible to later `eval`s
and to the program itself (through `eval` -- a name first defined this way has no
compiled direct reference), and a `set!` of one of the program's variables assigns
it: the program reads what `eval` wrote.

```scheme
(define (execute exp) (apply (eval (car exp) user-initial-environment) (cdr exp)))
(display (execute '(> 5 3))) (newline)
(eval '(define (fact n) (if (= n 0) 1 (* n (fact (- n 1))))) (interaction-environment))
(display (list (eval '(fact 10) (interaction-environment))
               (eval '(let loop ((i 0)) (if (= i 10000) i (loop (+ i 1))))
                     (interaction-environment))))
(newline)
```

```
#t
(3628800 10000)
```

Inside `eval`, a named `let`, a `do` and a procedure calling itself run in constant
stack; every other call uses it. `define-record-type`, `define-values`, `let-values`,
and `import` are refused by name inside `eval`. A
compiled program's `eval` resolves a built-in procedure only when the program spells its
name somewhere -- as a symbol, quoted data included, or inside a string -- while the
interpreter resolves them all.
