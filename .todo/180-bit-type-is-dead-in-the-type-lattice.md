# The `bit` type answers NIL everywhere -- a residue of the uppercase cutover

Measured on the native binary at `5c0332c6` (all four backends share the table,
so this is not backend-specific):

```lisp
(print (subtypep 'bit 'integer))   ; => NIL   -- Common Lisp says T
(print (typep 1 'bit))             ; => NIL   -- Common Lisp says T
(defun st (a b) (subtypep a b))
(print (st 'bit 'integer))         ; => NIL   -- the runtime path agrees, wrongly
```

Two independent gaps, found while making the emitted `%subtypep-ancestor-table%`
deterministic (`.todo/179`):

1. **The lattice entry is spelled in lowercase.** `LispMacroExpander`'s
   `SUBTYPEP_PARENTS` has `entry("bit", List.of("INTEGER"))` while every other
   key in the map is uppercase. The reader upcases every symbol
   (`.kb/reader-case-upcase.md`), so nothing can ever match the key: the entry
   is dead, and its only effect today is to put a stray lowercase `bit` into the
   emitted ancestor table. `git log -S` puts it at `01256210` ("Cut the symbol
   model over to uppercase-canonical (Approach A2)") -- one entry the cutover
   missed, not a deliberate exclusion. `LispNames.BIT` is already `"BIT"`.

2. **`typep` has no `BIT` test at all**, which is why fixing (1) alone would
   make the two disagree (`subtypep` yes, `typep` no). CL's `bit` is
   `(integer 0 1)`, so the test is `(and (integerp x) (>= x 0) (<= x 1))` -- it
   belongs with the other integer-family tests in `LispMacroExpander`'s type-test
   builder, and needs the same treatment in the runtime `%typep` tag table so a
   computed `(typep x y)` agrees.

Deliberately NOT fixed in `.todo/179`'s pass: that change is a compile-time
performance fix whose acceptance bar is byte-identical output, and correcting the
key changes the emitted table's contents. Do the two together, with a ci-spec case
covering `subtypep`/`typep` over `bit` on all four backends (both the literal fold
and the computed/runtime path -- they are different code).

Worth checking in the same pass whether any OTHER name survived the uppercase
cutover in lowercase: grep the static type/name tables for a lowercase string
literal used as a symbol name.

Related: `.kb/declarations-type-checks.md` (the shared type-specifier tests),
`.kb/reader-case-upcase.md`, `.todo/156`.
