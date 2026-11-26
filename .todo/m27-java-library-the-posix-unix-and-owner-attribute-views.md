# m27. Java library: the posix, unix and owner attribute views

Difficulty: Medium

Split from l68 (`.kb/java-frontend.md`, "What l68 added"). Read
`.kb/java-frontend-provenance.md` first.

Refused by name: `Files.getFileAttributeView`, `getOwner`, `setOwner`,
`readAttributes(path, PosixFileAttributes.class)`, `PosixFileAttributes`,
`PosixFileAttributeView`, `UserPrincipal`, `GroupPrincipal`,
`FileSystem.supportedFileAttributeViews`. By name, `readAttributes(path, "posix:*")`,
`getAttribute` and `setAttribute` of the `posix`, `unix` and `owner` views answer
`View 'posix' not available` (a JDK 25 on Linux: `[owner, dos, basic, posix, user, unix]`).

What is missing below: `posix:*` names an owner and a group (a user name from a uid needs
the host's user database, which no backend asks today); `unix:*` adds `uid`, `gid`, `ino`,
`dev`, `rdev`, `nlink`, `ctime` and `mode`. `%file-stat` would take the numbers (nil on
both WASI backends, whose stat has `dev`, `ino` and `nlink` but no owner); a name from a
number is a primitive of its own on the interpreter and the JVM. On a `.wasm` the views
are absent, as l68 made the permission bits (`UnsupportedOperationException`, a
`getFileAttributeView` of null), unless measurement shows a part (`ino`, `nlink`) worth
answering. Measure each answer against a JDK first; a program that reaches no file stays
byte-identical.
