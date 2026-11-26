# l22. pgjdbc compiled by rontolisp (long-term)

Difficulty: High

**Read `.kb/java-frontend-provenance.md` before writing anything.** Split from l01
(2026-11-22): the user chose a JDBC driver of our own (`jdbc-postgresql/`,
`.kb/jdbc-postgresql.md`), which is what a program uses today. pgjdbc 42.7.14
(`org.postgresql:postgresql`, BSD-2) from its sources jar stays a long-term goal: it is the
driver existing programs name, and what it needs is what any large library needs.

Measured (2026-11-22, `.kb/java-frontend.md`, "pgjdbc measured"): pgjdbc starts threads, so
on one stack the driver is resumable bodies -- a lower bound of 7.80 MB of wasm (7.88 MB a
component, 52 s to compile) against 0.78 MB and 11 s for the same program over
`jdbc-postgresql/`.

**First part made (2026-11-25, `.kb/java-frontend.md`, "pgjdbc: what a connection meets that
no other item makes"; `.todo/artefacts/l22-pgjdbc-on-rontolisp/`)**: the refusals no other
item owns but `System.getenv()`. The census of `Ladder` now: 79 refusals with no shells (94 at
the start, 197 at l01), 7 once every class is there (19, 36), 116 members of the missing
classes named.

## What is left, in the order a connection meets it

- **`System.getenv()`** (3 sites: `System.getenv().get(name)` in `PgPassParser` and
  `PgServiceConfParser`, at every login without a password). The map needs the whole
  environment, so a primitive listing it on the four backends, which `.todo/m09`
  (Scheme's `get-environment-variables`) needs too: the interpreter and the JVM from the
  host's map; Preview 1 from `environ_get` without a new fixed function index (every
  module's bytes would move: `_getenv`'s body answering the list where the program asks for
  it, gated as `usesBidirectionalOpen` gates `_open`); the component from
  `get-environment`, which `environment.lisp` already binds. A wasm program sees only what
  its host gives it (`wasmtime --env`), so `JavaSpecE2eTest` needs an environment given to
  every leg (the interpreter's in a process of its own) to pin it.
- `Calendar` / `GregorianCalendar` / `TimeZone` / `SimpleTimeZone`: `.todo/l79` (63 of the
  79).
- `NumberFormat.getIntegerInstance`, `setGroupingUsed(false)`, `parseObject(String,
  ParsePosition)`: the server's version, read at every connection (`ServerVersion`); the
  class is `.todo/k87`'s.
- `java.lang.reflect.Array` (6) and `MethodHandle` (2): `.todo/l65`. Reflection where the
  program runs, which no census shows: `Driver.doPrivileged` asks for
  `java.security.AccessController` by name and `TimestampUtils`' static initializer reads a
  private field of `TimeZone` (`.todo/l64`, `.todo/m03`).
- Decided and made by l86 (2026-11-25; `.kb/java-frontend.md`, "A library from the source
  path"): a statement of a library naming what the subset lacks (TLS `.todo/l15`, GSS, JAAS,
  `ManagementFactory`, `Files`' attribute members, the XML types) is a warning where the
  program is compiled and throws `NoClassDefFoundError` / `NoSuchMethodError` where it runs;
  no value is of a class the subset lacks. So `Ladder` compiles past them; what is left is
  what a connection runs: take the census, then run `Ladder` without it and read the warnings
  against what throws.
- Then the 71 lambdas of 26 KB each (1.84 MB: one shape, a test of one argument's class
  against some 31 classes; what makes them was not found), and the size again. Take the
  census after each (`census.sh` of l01's artefacts).
- Left by the first part, not needed by pgjdbc: `toMap(identity(), identity())` inside
  `collectingAndThen`, whose `T` only the stream gives (a joint inference over nested calls).

Done means what l01 meant: `probes/Ladder.java` of l01's artefacts prints the JDK's lines on
the interpreter, the JVM, `--native` and `--component`; `ClPostgresE2eTest` has the harness
(its JDBC leg takes a source path). Report the sizes beside `jdbc-postgresql/`'s.
