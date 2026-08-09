# pathname

`(pathname pathspec)`

The pathname the designator names: a pathname is returned unchanged, a string
yields a fresh pathname carrying it as the namestring, and anything else
signals. This is the canonical constructor the whole family funnels through --
`#P"..."` in source denotes the same value this builds at run time.

```lisp
(pathname "d/notes.txt")   ; => #P"d/notes.txt"
```

`(pathname #P"d/notes.txt")` is the argument itself; `(pathnamep (pathname
"x"))` is `T`.

## Backend support

All four backends -- one definition in rontolisp source, spliced into the
program when it is referenced.
