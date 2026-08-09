# namestring

`(namestring pathname)`

The namestring of a pathname designator: a pathname value unwraps to the
namestring it carries, a string is already one and passes through, and anything
else signals. Portable code calls it where a pathname object has to be turned
into a string before printing, concatenating or handing it outside Lisp.

`uiop:namestring` names this same function -- real UIOP re-exports Common
Lisp's, and so does this -- and so does `uiop:native-namestring` (a rontolisp
namestring already is the host spelling).

```lisp
(namestring #P"/tmp/data.json")   ; => "/tmp/data.json"
```

`(namestring "/tmp/data.json")` is the string itself.

## Backend support

All four backends -- one definition in rontolisp source, spliced into the
program when it is referenced.
