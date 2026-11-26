# m86. `--dump-ir` expands a macro name in an argument position

Difficulty: Low

`BuiltinMacroLowering` (the dump's lowering of the built-in macros) takes a list's tail as a
form: `(- time (+ 1 1))` dumps as `(- LET ((|__time_start| ...)) ...)`, the tail
`(time (+ 1 1))` expanded as the `time` macro. The program itself is right (prints 8 on the
interpreter and Preview 1); only the dump is wrong, and the class's contract is that the dump
runs identically. Seen in `java.lisp`'s `%java-sleep-until`, whose parameter is `time`.

```lisp
(defun f (time) (- time (+ 1 1)))
(print (f 10))
```

`rontolisp f.lisp --dump-ir -o f.wasm` shows the expansion.

- Fix the walk so an argument list is walked element by element, never as a form.
- Pin: extend a `--dump-ir` test of `RontoLispCliTest` (dump, then run the dump).
