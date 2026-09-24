# `--native` on Linux: win back the 0.98 MB the static-glibc stub costs every output

Difficulty: Medium

The Linux runner stub links glibc statically so outputs have no glibc floor
(`.kb/native-output.md`). That made every x86_64 output 0.98 MB bigger (hello 2.0 ->
3.0 MB; stub 2,987,040 B against 2,004,424 dynamic). musl would cost 0.1 MB but ran
`gc.lisp` 13-15% slower, from its string functions (Traps, "musl is slower").

## Plan

- Measure where the static glibc's extra `.text` (+735 KB on x86_64) comes from
  (`-Wl,--print-map` or `bloaty`); some of it may be droppable (IFUNC variants, stdio,
  locale) with linker flags alone.
- Otherwise try musl with the stub's own `memmove`/`memcpy`/`memset` (the symbols
  override libc.a's members at static link time), vectorised for the small copies the
  copying collector makes. It must match glibc on `gc.lisp` (pinned to one core, user
  cycles) on x86_64 AND aarch64, and needs exhaustive size/overlap tests of its own.
- Keep whichever is smaller at equal speed; update the size numbers in
  `doc/{en,ja}/compiling/native.md`.
