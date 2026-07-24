# A struct instance neither prints nor reads as `#S(...)`

`(make-point :x 1 :y 2)` prints as `(%STRUCT-POINT 1 2)`, and `#S(POINT :X 1 :Y 2)`
in source is a read error. Both are documented as out of scope
(`doc/*/guides/missing-features.md`, `doc/*/reference/special-forms/defstruct.md`,
`.kb/defstruct.md`), but nothing about the design forbids them -- the metadata
needed for both already exists, it is simply not reachable from the printer or the
reader.

## Why it is missing

An instance is a tagged proper list `(%struct-<name> v1 v2 ...)` and `defstruct`
expands into ordinary defuns, so the slot NAMES live only in the expansion-time
registry (`LispMacroExpander.expandDefstruct` -> `structAccessors`, keyed
accessor -> slot position). Consequences:

- The printer sees a cons whose car happens to be a symbol. It has no way to
  learn that `%STRUCT-POINT` has slots `X` and `Y`, so it prints the list.
- The reader sees `#S(POINT :X 1 :Y 2)` and has no way to learn POINT's slot
  ORDER, so it cannot build the tagged list. `LispLexer` rejects `#S` outright.

The same is true of CLOS instances (`(%class-<name> ...)`), which print as lists
instead of `#<POINT ...>`. Whatever wiring fixes structs should fix those in the
same pass.

## The two mechanisms that already exist

- **Expansion-time metadata that survives into a compiled program**:
  `%class-slot-defs` (`LispMacroExpander.expandClassSlotDefs`,
  `LispNames.CLASS_SLOT_DEFS_INTERNAL`) expands into a membership dispatch over
  every registered class, so a compiled program can ask for a class's slot list
  at runtime. A `%struct-slot-defs` twin is the printer's lookup table on the
  compile path.
- **A reader marker resolved downstream**: `#.datum` lexes to
  `(%read-eval datum)` (`LispLexer:138`, `LispNames.READ_EVAL`) and the consumer
  resolves it. `#S(...)` can lex to the same kind of marker and be folded by the
  expander, which HAS the registry -- no dependency from `reader` onto the
  registry, and one implementation for all backends.

## Scope

Split by cost; the first two are the user-visible complaint.

1. **Print `#S(POINT :X 1 :Y 2)`.** Each backend renders a value through one
   helper: the interpreter through `LispVal.print()` / `display()`
   (`LispCons.display`), the JVM through the generated `_lispToString` /
   `_lispToDisplayString` (`JvmRuntimeBuilder:178` / `:499`), WASM through
   `FUNC_PRINC_VAL` / `FUNC_PRINT_VAL` / `FUNC_PRIN1_TO_STR`
   (`WasmRuntimeBuilder.buildToStringBody`). Add one branch: car is a symbol
   whose name starts with `%struct-` -> `#S(<name> :<slot> <value> ...)`, slot
   names from the emitted table. CL prints `#S` under both `princ` and `prin1`,
   so both renderers change. `--no-gc` rejects `defstruct` at the top level, so
   only three of the four backends are in play.
   The interpreter is the real design decision here: `display()` is a method on
   the value with no access to `LispEvaluator.structAccessors`, so either the
   registry is threaded into the print path, or struct layouts move to a
   process-wide registry the printer can consult.
2. **Read `#S(...)` from source.** Lex to a marker, fold it in
   `LispMacroExpander` against `structAccessors` into a literal
   `(%struct-<name> v1 v2 ...)`. `#S` slot values are read as data, not
   evaluated, so folding to a literal is semantically right AND makes `'#S(...)`
   inside quoted data work for free -- which a runtime-constructing design would
   not. Unknown struct name, unknown slot name, and a duplicate slot are read
   errors. Slots absent from the literal take their initform (CL says the value
   is unspecified if the slot has no initform).
3. **Read `#S(...)` at runtime (`read` / `read-from-string`).** On the compile
   path the reader is generated code, so this needs the slot table reachable
   from the emitted reader, not just from the expander. Separable: ship 1+2 and
   keep this documented as unsupported if it does not fall out cheaply.

## The representation question -- decide it before starting

The CL-faithful design is a dedicated struct object type instead of a tagged
list: `consp` would become nil on an instance, `print-object` would have a place
to hook, and CLOS's `#<...>` printing would follow. It is NOT proposed here
because conditions are `%class-` tagged lists too
(`WasmHandlerCaseCompiler` / `JvmHandlerCaseCompiler` build
`(list '%class-simple-error msg nil)` directly), jzon's CLOS stringify reads
instances through `%class-slot-defs`, and both WASM paths would need a new value
representation. Printing and reading do not require it. If the eventual answer is
a real object type, say so in `.kb/defstruct.md` when this lands, so the printer
branch is understood as an interim shape and not as the settled design.

## Verification

- All three defstruct-capable backends print the same `#S(...)` text for a
  struct with a string slot, a nil slot, a nested struct, and a struct inside a
  list -- plus `princ`, `prin1`, `format ~a`/`~s`, and `print`.
- `#S(...)` in source, in a quoted list, and inside a backquote template.
- The printing change moves existing output: the ci-spec `defstruct-*` cases,
  the `doc/*/reference/special-forms/defstruct.md` examples (`DocExamplesTest`),
  and the missing-features rows in both languages must be updated in the same
  commit. Run the native-binary `CiSpecE2eTest`, not just `./mvnw test`.
- Nothing that does not involve a struct or a CLOS instance may change its
  printed output.
