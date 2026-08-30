# A cyclic cons still overflows every printing operator

Difficulty: High

## Where this comes from

Todo 584 gave the default INSTANCE renderer a cycle guard on all four backends:
an instance already on the current rendering path -- or the frame past the
256-frame depth cap -- prints as `#` (`.kb/pretty-printer.md`, "A cyclic
instance graph"). The cons renderer was deliberately left out of that change:
it is a separate arm in each backend (the interpreter's `LispCons.print`, the
JVM's `_consToString` pair, the wasm printers' list loop), the everyday cycle a
user hits is the instance one (a scene graph), and CL's actual answer for data
cycles is `*print-circle*`, which deserves its own design rather than a guard
bolted onto one arm at a time.

## What still breaks

```lisp
(let ((x (list 1))) (setf (cdr x) x) (print x))   ; => StackOverflowError
```

-- on the interpreter and the JVM class, and an exhausted-stack trap mid-write
on both WASM backends. Same for a rank-1 general vector holding itself.

## What to decide

- Whether to implement `*print-circle*` proper (`#1=`/`#1#` labels need a
  pre-scan pass over the value) or to extend the instance guard's path/depth
  discipline to the cons and vector arms (finite output, no labels, no
  variable to honor).
- `*print-circle*` exists and is `nil` today, documented as "no circle
  detection" in `.kb/pretty-printer.md`'s table; whichever way this goes, that
  table row and this behavior must move together.
- Whatever the choice, the todo-584 rule carries over: a cycle must print
  something finite or signal a condition a program can handle, byte-identically
  on all four backends, and the instance guard's marker (`#`) and cap (256,
  `LispInstance.MAX_RENDER_DEPTH`) are the precedent to stay consistent with.
