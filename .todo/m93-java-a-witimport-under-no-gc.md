# m93. Java: a `@WitImport` under `--no-gc`

Difficulty: Medium

A Java program reaches a WIT interface through an object: `ServiceLoader.load(Clock.class)
.findFirst().orElseThrow()` answers the binding the front end writes, and the program calls its
methods (`.kb/java-frontend.md`, "WIT imports"). `--no-gc` has no heap object, so the lookup is
refused (`--no-gc: unsupported operation 'LAMBDA' in function 'Nap.pause(int)'`) and a Java
reactor compiled `--no-gc` calls no WIT import at all, sync or async -- though the scalar backend
itself imports one (`rontolisp:wit-import`, an `async func` under `:blocking t`:
`.kb/no-gc-scalar-wasm.md`, "An `async func`: a call that waits"). Its `async func` EXPORT lifts
async already, with nothing in the scalar subset to wait on.

- **What to decide first**: what the binding is where no object exists -- the lookup and the
  receiver folded away at compile time when the interface has no resource and the binding no state
  (each method call a call of the import), or a form of the annotation that declares static
  methods. Measure what a Java reactor's `ServiceLoader` chain lowers to and whether the fold is
  exact (an `Optional` nothing reads, a receiver nothing reads: `NoGcStructures`' "a structure
  nothing reads").
- **Scope**: the flat types the scalar component's instance type spells (the integers, `f64`,
  `bool`, `string`); a resource, a record and a stream stay refused at the WIT line.
- **Async**: the directive already carries `:blocking t`; an export reaching one is an
  `async func` of the world, which the backend checks by the call path.

Pins: `JavaWitImportTest` (a `--no-gc --component` leg of the flat interface, a wait on
`wasi:clocks/monotonic-clock@0.3.0`), `JavaReactorTest`; `doc/{en,ja}/java/wit.md`.
