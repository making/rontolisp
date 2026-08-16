# file-namestring directory-namestring host-namestring

`(file-namestring pathname)` -- `(directory-namestring pathname)` -- `(host-namestring pathname)`

The string-valued components of a namestring. `file-namestring` is the name-and-type part -- everything after the last `/`, or the whole namestring when there is none -- and `directory-namestring` is what comes before it, up to and including that `/`. The two are exact complements: concatenating them always gives [`namestring`](namestring.md) back. `host-namestring` is always `""`, because a rontolisp namestring carries no host syntax; [`pathname-host`](pathname-host.md) is the `nil`-answering spelling of the same absence, and Common Lisp requires a string here. All three accept either spelling of a pathname designator and signal on anything else, exactly like `namestring`.

```lisp
(list (file-namestring #P"/a/b/c.txt")
      (directory-namestring #P"/a/b/c.txt")
      (host-namestring #P"/a/b/c.txt"))
; => ("c.txt" "/a/b/" "")
```

A namestring that names a directory has no file half, and one without a `/` has no directory half:

```lisp
(list (file-namestring "/a/b/") (directory-namestring "a.txt"))
; => ("" "")
```

The leading dot of a dotfile belongs to the name, the same rule [`pathname-name`](pathname-name.md) follows: `(file-namestring "/a/.bashrc")` is `".bashrc"`.

## Backend support

All four backends -- one definition in rontolisp source, spliced into the program when it is referenced.
