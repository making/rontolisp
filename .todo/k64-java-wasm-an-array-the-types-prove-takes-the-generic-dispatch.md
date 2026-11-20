# k64. Java on wasm: an array the types prove takes the generic dispatch

Difficulty: High

Left by k56 (2026-11-20). An `int` or `long` operation over operands the types prove has no
fallback now (`.kb/declarations-type-checks.md`, "Declared integer ranges"), but every array
access of a Java program is still the inline four-way dispatch of an undeclared array
(`.kb/declarations-type-checks.md`, "Declaration-driven array emission": 196 B a rank-1
`aref`, 232 B and up an `%aset`), and a `long[]` read in a fused tree is an aref leaf that
bails on the general array it always is. The front end knows each array's element type;
it declares none, since a Java array variable may hold `null`, which must raise
`NullPointerException` where it is read, not trap on a cast.

- **Measured** (k56's build): what is left of the zone reader (`ZoneChanges.region`, `find`,
  `rule`, `written`, `number`: 13,217 B of wasm) is mostly its 17 `aref`, 14 `%aset` and 13
  `length` sites; `region` alone calls `_idx_ref` and `_arr_check_rank` 20 times each.
- **To decide**: how the backend learns the kind and keeps the null semantics -- a
  declaration `(or null (simple-array ...))` the array kinds read with a null test ahead of
  the single arm, or a typed access of the core forms -- and whether a fused `long[]` read
  takes a general array's element with a trusted unbox instead of bailing. The JVM's
  accesses go through `_ivAref1` and the like and need no kind.
- **Measure before and after**: the zone reader, `examples/java/yavi/Booking.java` and
  `Member.java` (wasm), `SieveLoop` (`.kb/java-frontend.md`, "Numbers").
