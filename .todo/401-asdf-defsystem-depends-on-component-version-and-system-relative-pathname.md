# 401. ASDF: `:defsystem-depends-on`, `component-version`, `system-relative-pathname`

Difficulty: Medium

Three ASDF gaps the dexador spike (`.todo/396`) hit, in the order they fire.

## 1. `:defsystem-depends-on` is a hard error

`dexador.asd`'s first option kills the load before anything else is read:

```
error: ASDF:DEFSYSTEM dexador: unsupported option :DEFSYSTEM-DEPENDS-ON
       (supported: :name :description ... :rontolisp-features)
```

The option names systems needed at .asd READ time. This is not a dexador
quirk -- `ShimLibraries` already carries `mgl-pax-bootstrap` as a whole shim
system *because* its real `.asd` uses it, and the same reason is written twice
in `BuiltinSystems`. Closing this retires that workaround.

Complication: dexador's `:defsystem-depends-on` names **trivial-features**,
which is itself unloadable here -- its `.asd` ends in
`(ERROR "Sorry, your Lisp is not supported. Patches welcome.")` for any
implementation it does not recognize. Its whole job is pushing platform
features (`:unix`, `:linux`, `:little-endian`, ...) onto `*features*`, and
rontolisp's `Features` decides those itself. So:

- teach `AsdfSystems.parseDefsystem` the option, resolving each named system
  through the ordinary shim/built-in/real ladder before the rest of the parse;
- add a **trivial-features shim system** whose content is the feature
  declarations rontolisp's own `Features` already implies (the
  `:rontolisp-features` channel is the mechanism -- `.kb/asdf.md`), not an
  empty stub, since downstream `#+unix` reader conditionals are the reason
  anyone depends on it.

Note the `.asd`-is-data premise still holds: nothing here evaluates the
dependency, it only has to be LOADABLE and to contribute features.

## 2. `asdf:component-version` does not exist

dexador builds its User-Agent from
`(asdf:component-version (asdf:find-system :dexador))`, and the reader is
absent: `The symbol COMPONENT-VERSION is not external in the ASDF package`.
`:version` is in `IGNORED_OPTIONS`, so the registry record does not carry it
either -- both halves are the work. todo-374 built the component metaobjects
(`asdf.lisp`, `%asdf-system-record`); this adds one field to the record, one
slot to `asdf:system`, and one reader, on every backend. `(:version "uiop"
"3.1.1")` in a `:depends-on` already parses, so the SPELLING is understood
today; only the value is thrown away.

## 3. `asdf:system-relative-pathname` has no compile-path form

Compiling a program that loads trivial-mimes warns
`the function ASDF:SYSTEM-RELATIVE-PATHNAME is undefined; compiled as a
call-time error`. It works in the interpreter, so this is the
`AsdfRuntimeLibrary` splice missing an entry (the `CompileTimePathnameFolder` /
`asdf.lisp` seam in `.kb/asdf.md`) -- the system's base directory is already in
the baked `%asdf-registry%` table, so the runtime form is a concatenation.

## Pinning

One `.asd` fixture per gap under `src/test/resources/`, driven on all four
backends, and `.kb/asdf.md` updated -- it currently states "no
`:defsystem-depends-on`" as a boundary, which this item moves.
