# asdf:component-pathname

`(asdf:component-pathname component)`

Returns the component's pathname as a namestring: for a **system**, the
directory its `:pathname` names below the `.asd`'s, with a trailing `/`; for a
**module**, its directory likewise; for a **file** (a source file or a static
file, an entry of [`asdf:component-children`](asdf-component-children.md)), the
resolved path of the file itself. `component` may be a component
[`asdf:find-system`](asdf-find-system.md) or
[`asdf:find-component`](asdf-find-component.md) answers, or a plain name
designator (a string, keyword or symbol) — the designator names a system, which
must be registered (loaded, or currently loading).

This is how a library locates data files bundled beside its own sources:
local-time finds its `zoneinfo/` repository with
`(asdf:component-pathname (asdf:find-system :local-time nil))`.
[`asdf:system-relative-pathname`](asdf-system-relative-pathname.md) composes the
`.asd`'s directory, which a system's `:pathname` does not move, with a relative
name in one call.

```console
(asdf:load-system "my-lib")
(print (asdf:component-pathname (asdf:find-system "my-lib")))
```

## Backend support

Works on all four backends. The interpreter answers from its system registry at
run time; the compile paths fold the call to a literal namestring when the
system name is a literal (a literal `find-system` around it included), which is
the shape every library uses — anything else reads the registry the compiled
program carries.
