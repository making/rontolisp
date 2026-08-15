# translate-logical-pathname

`(translate-logical-pathname pathname &key)`

The pathname itself. Every rontolisp pathname is PHYSICAL -- there are no
logical hosts and no `logical-pathname-translations` table to consult -- so the
translation is the identity, which is what Common Lisp prescribes for a physical
pathname argument. Portable code that normalizes a path through this before
opening it therefore works unchanged.

```lisp
(namestring (translate-logical-pathname "d/a.txt"))   ; => "d/a.txt"
```

[`logical-pathname`](logical-pathname.md) is the other half of that decision: it
always signals, because nothing here can be a logical pathname.

## Backend support

All four backends -- one definition in rontolisp source over primitives every
backend has.
