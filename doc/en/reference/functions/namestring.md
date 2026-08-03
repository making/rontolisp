# namestring

`(namestring pathname)`

The namestring of a pathname. A rontolisp pathname IS its namestring, so this is
the identity on a string, and anything else is not a pathname designator and
signals. Portable code calls it where a pathname object would have to be turned
into a string before printing or opening it; here that step is already done.

`uiop:namestring` names this same function -- real UIOP re-exports Common Lisp's,
and so does this.

```lisp
(namestring "/tmp/data.json")   ; => "/tmp/data.json"
```

## Backend support

All four backends -- one definition in rontolisp source, spliced into the program
when it is referenced.
