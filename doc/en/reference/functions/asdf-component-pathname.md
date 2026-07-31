# asdf:component-pathname

`(asdf:component-pathname system)`

Returns the directory a loaded system was found in, with a trailing `/`. In real
ASDF this is the base pathname of any component; rontolisp only ever materializes
a *system* as a component object -- its downcase-canonical name, a string, which
is what `asdf:find-system` answers with -- so here it is the system's source
directory under the name libraries actually call.

This is how a library locates data files bundled beside its own sources: local-time
finds its `zoneinfo/` repository with
`(asdf:component-pathname (asdf:find-system :local-time nil))`. The system must be
registered (loaded, or currently loading); an unknown name is an error.
[`asdf:system-relative-pathname`](asdf-system-relative-pathname.md) composes this
with a relative name in one call.

```console
(asdf:load-system "my-lib")
(print (asdf:component-pathname (asdf:find-system "my-lib")))
```

## Backend support

Works on all four backends. The interpreter answers from its system registry at
run time; the compile paths fold the call to a literal namestring when the system
name is a literal, which is the shape every library uses.
