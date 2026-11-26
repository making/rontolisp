# m50: hard links and a file's key

What `.kb/java-frontend.md`, "Hard links and a file's key", was taken with (2026-11-26,
JDK 25.0.5.1, wasmtime 51.0.0, Linux). Nothing of a JDK is here.

- `probes/`: programs written here and what a JDK printed for them (`*.jdk.txt`), then each
  leg the subset compiles (`*.interp.txt`, `*.jvm.txt`, `*.wasm.txt`, `*.comp.txt`). The
  directory a probe runs in is `tools/stage.sh`'s. `Hard.java` reads the unix view, so its
  WASI legs are `HardW.java` (the same without it, plus a key after a delete and a write).
  The legs differ from the JDK's only in the key's class name, the leg's own path and, on
  wasm, a link outside the preopens (`tmp`: `Operation not permitted`). `Cross.java OTHER`
  links into a second preopen named by its absolute path: made on every leg.
- `probes/p1.wat`, `p1.out`: Preview 1's `path_filestat_get` and `path_link` called raw under
  wasmtime (`--dir . --dir OTHER::other`): the device is 1, the inode a 64-bit hash equal for
  two names of one file, and `path_link`'s errnos (exist 20, noent 44, acces 2, perm 63 for a
  directory and above the preopen, notdir 54, inval 28 for `symlink_follow`).
- `tools/run.sh LEG PROBE` (`CP` the classes of a build): a probe staged and run on one leg.
- `sizes/Hard.java` joined m27's `sizes/progs` for the measurement in the kb.
