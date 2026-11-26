# m57. Java library: an `EnumSet` without every enum's `values()`

Difficulty: Medium

The library's `EnumSet.noneOf` (and `of`, `allOf`, `range`, which go through it) asks
`Class.getEnumConstants()`, and a program reaching that counts every enum class it holds as
calling its `values()` (`.kb/java-frontend.md`, "A class object initializes nothing; an
enum's constants do"). A file program reaches `java.time` through `FileTime`, so one
`EnumSet` of `PosixFilePermission` carries the `values()` of `ChronoField`, `Month`,
`DayOfWeek` and the rest.

Measured (2026-11-26, wasm): a program reading the basic view by name, 268,969 B, became
340,630 B once `Files.readAttributes(path, String)` could build a `posix:permissions` set;
of that, 13,515 B is the views' own code (the same program with `getPosixFilePermissions`
already in it: 329,595 -> 343,110), the rest `getEnumConstants` and what it holds. A small
program with one `EnumSet` of `PosixFilePermission` and no other enum: 17,649 B over the
same program with a `HashSet`.

Find a way for an `EnumSet` whose element class the call site names as a literal to take
that class's constants alone (a lowering of `noneOf(X.class)`, or the class handing its
universe), without changing what `getEnumConstants` answers. Measure the file programs
and a program of `EnumSet`s over several enums before and after; a program naming no
`EnumSet` stays byte-identical.
