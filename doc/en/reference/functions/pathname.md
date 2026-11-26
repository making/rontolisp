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

A file stream designates the pathname it was opened with, closed or not, so
`(pathname s)` of a stream from `(open "d/notes.txt")` is `#P"d/notes.txt"`; so
do [`namestring`](namestring.md), [`truename`](truename.md), `probe-file`,
`delete-file`, `open` and the rest of the path-taking family. SBCL answers that
pathname merged with its default directory, so absolute. A string stream
designates none and signals.

## Backend support

All four backends -- one definition in rontolisp source, spliced into the
program when it is referenced.
