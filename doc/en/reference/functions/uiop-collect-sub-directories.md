# uiop:collect-sub*directories

`(uiop:collect-sub*directories directory collectp recursep collector)`

Walks a directory tree. Each directory reached is passed to `collectp`; when that
answers true it is passed to `collector`. Each of its subdirectories is passed to
`recursep`; when that answers true the walk descends into it. Every directory
handed to the three functions is a pathname in directory form (trailing `/`),
including the root, so the shape is the same at every level. Returns `nil`.

The `(constantly t)` pair is the "walk everything" spelling:

```console
$ cat walk.lisp
(uiop:collect-sub*directories "src/" (constantly t) (constantly t)
                              (lambda (dir) (print dir)))
$ rontolisp walk.lisp
#P"src/"
#P"src/main/"
#P"src/test/"
```

## Backend support

All four backends, over the same one primitive [`directory`](directory.md) uses.
