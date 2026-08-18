# 445. A `print-object` method defined BELOW its first use crashes the JVM backend

Difficulty: Medium

Found while landing `.todo/437` (nested `print-object` dispatch); it is NOT that
bug and it predates it -- the repro below fails identically on the commit
before.

## The defect

A `defmethod print-object` at a NON-top-level position (inside a `let`, a
`progn` body, ...) registers in the class registry at expansion time, so the
compile paths route the printer through the generic from the very first print.
But the method's runtime table entry is only written when the `defmethod` FORM
executes. A print that happens before that dispatches into an empty slot:

```lisp
(defclass late () ())
(let ((o (make-instance 'late)))
  (print o)                                                  ; <-- here
  (defmethod print-object ((x late) s) (format s "#<LATE!>"))
  (print o))
```

```console
$ rontolisp t.lisp                 # interpreter: fine
#<LATE>
#<LATE!>
$ rontolisp t.lisp -o Prog.class && java Prog
Unhandled condition: Cannot load from object array because "<local4>" is null
Exception in thread "main" java.lang.NullPointerException: ...
```

The interpreter answers the built-in rendering for the first print (its registry
has not seen the method yet) and the method's text for the second, which is what
CL does. The JVM backend crashes with a raw `NullPointerException` -- no
condition, no message a program can act on.

## What to decide first

The interpreter and the compile paths cannot agree on the FIRST print here: the
compile path's registry is whole-program by construction. So the question is not
"make them equal" but "what does the compile path do with a routed tag whose
method has not run yet". Two defensible answers:

- the built-in rendering (matches the interpreter, and CL, for this program), or
- `no-applicable-method`, which is what a non-printer generic already signals.

Whichever it is, a raw `NullPointerException` is not it. Check the WASM backend
in the same pass -- the dispatch table is built the same way there.

## Watch

- The same hole exists for any generic whose dispatch slot is filled by a
  non-top-level `defmethod`; `print-object` is only where it is easy to hit,
  because the printer calls the generic without the program asking.
- `.kb/clos.md` (dispatch tables, `no-applicable-method`) and the
  `print-object` section of the same file.

## Acceptance

The repro prints on all four backends instead of crashing, with the chosen
answer written into `.kb/clos.md` beside the reason; a per-backend test plus a
ci-spec case.
