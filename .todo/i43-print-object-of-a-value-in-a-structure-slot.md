# i43. `print-object` is not consulted for a value in a structure slot

Difficulty: Medium

`.kb/clos.md` lists this as "Still not walked (trigger)". The trigger, measured 2026-11-05 on the JVM after `.todo/i26`.
SBCL 2.2.9 prints 339 characters, the interpreter and the JVM 25-28:

```lisp
(defstruct item a)
(defclass thing () ())
(defmethod print-object ((o thing) stream)
  (print-unreadable-object (o stream :type t)
    (format stream "~a" (make-string 300 :initial-element #\x))))
(print (length (format nil "~a" (make-item :a (list (make-instance 'thing) nil t)))))
```

Consumer: sento logs a cancelled `message-item/dp` with `(log:warn "~a: item got cancelled ...: ~a" name popped-item)`,
and its `handler-fun-args` holds an actor. The slot prints raw: 353876 characters for one idle actor (a method on
`actor` would print one line), walking the live system. About one run in thirteen of an `ask-s` with a `:time-out`,
the walk meets a list another thread is growing and log4cl reports it:

```
ERROR - Caught TYPE-ERROR index out of bounds Appender #<LOG4CL-IMPL:CONSOLE-APPENDER ...
```

(`Index 268 out of bounds for length 10` when the same `format` runs outside log4cl.) The program carries on. The walk
needs a slot enumeration that reproduces `#S(NAME :SLOT value ...)` byte for byte on all four backends. A class instance's
slots print nothing (`#<THING>`), so only the structure arm is missing.
