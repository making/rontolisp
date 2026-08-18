# user-homedir-pathname

`(user-homedir-pathname &optional host)`

Returns the user's home directory as a **directory** pathname -- the namestring
always ends in a separator, and the name and type components are nil. The value
comes from the `HOME` environment variable; the `host` argument is accepted and
ignored, since there is one host.

`nil` when `HOME` is unset, which Common Lisp allows and is the honest answer on
a WASI guest that was given no environment at all.

```console
$ rontolisp -e '(print (user-homedir-pathname))'
#P"/home/you/"
```

## Backend support

Works on all four backends: one definition in rontolisp source over the
per-backend environment primitive.
