# asdf:load-asd

`(asdf:load-asd pathname &key name)`

Loads the definitions in one `.asd` file, whatever the file is called, replacing what
the registry holds under their names. A file that is not there defines nothing, as in
real ASDF. An error while the file loads is an `asdf:load-system-definition-error`.
Returns the `asdf:define-op` instance.

```console
(asdf:load-asd "vendor/old/my-lib.asd")
(asdf:load-system :my-lib)
```

## Backend support

Works on all four backends for a path known at compile time: a top-level call whose path
is a literal, or built from the file's load context, is read while splicing. Any other
call signals on the compile paths, where nothing is loaded at run time.
