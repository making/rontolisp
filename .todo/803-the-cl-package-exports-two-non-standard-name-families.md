# The `cl` package exports two non-standard name families

Difficulty: Low

`PackageRegistryTest#theClPackageExportsNothingBeyondTheStandardNamesExceptTheDocumentedExtensions`
names them, and the ANSI suite bills them as one test:

```
FAIL NO-EXTRA-SYMBOLS-EXPORTED-FROM-COMMON-LISP
  got ((CL:WHILE CL:BOOLE-9 ... CL:BOOLE-16 ...)) want (NIL)
```

CLHS 11.1.2.1 lets the `common-lisp` package export the 978 standard names and
NOTHING else. `.todo/796` closed the missing half (+187, 0 regressed); this is
the extra half, worth exactly **1 test** -- so take it for the invariant, not
for the number.

## `boole-3` .. `boole-16` -- an invented spelling, fix it

`ClConstants.NAMES` carries `BOOLE-1` ... `BOOLE-16` with the values 1..16.
Only `boole-1` and `boole-2` are standard names; the other fourteen are
`boole-and`, `boole-andc1`, `boole-andc2`, `boole-c1`, `boole-c2`, `boole-clr`,
`boole-eqv`, `boole-ior`, `boole-nand`, `boole-nor`, `boole-orc1`, `boole-orc2`,
`boole-set`, `boole-xor`. Their VALUES are implementation-dependent, so the
change is a rename in `LispNames` + `ClConstants` (plus dropping the fourteen
from `PackageRegistry.CL_EXPORTED_ONLY`, where `.todo/796` had to list them as
missing standard names while the invented spellings sat beside them).

`boole` itself is unimplemented; implementing it over the renamed constants is a
prelude defun and belongs with `.todo/037`'s `logeqv`/`lognor` row.

**Cost gate**: ci-spec `standard-limits-and-boole-constants` prints all sixteen,
so this touches `ci-spec.yaml` and therefore requires the native E2E leg
(`.kb/running-backends.md`). That is why `.todo/796` left it.

## `while` -- decide, do not "fix"

`while` is rontolisp's OWN loop primitive: a special form every iteration macro
lowers to (`LispMacroExpander`, `Jvm`/`WasmWhileCompiler`, `NoGcWasmCompiler`),
and it sits in `cl` because that is where the built-in operators live. Moving it
to `rontolisp` would make bare `(while ...)` stop resolving in `cl-user`, which
is a LANGUAGE change and a documentation change, not a registry one -- and
`PackageRegistry.NO_MACRO_FUNCTION` already carries the note that `while` is not
a CL name at all.

Either accept the one failing test as the price of the extension (and say so in
`.kb/packages.md`), or design the move with the doc pages and `examples/` in the
same change. Do not half-do it.
