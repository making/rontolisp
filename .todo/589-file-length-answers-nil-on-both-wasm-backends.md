# `file-length` answers nil on both WASM backends, so a size-driven reader traps there

Difficulty: Medium

Found 2026-08-30 while adding `geom:read-stl` (`.kb/geom.md`, "Reading a model
file"), which had to be redesigned around it.

`file-length` is documented as answering `nil` on the two WASM backends -- "no
WASI `filestat` call is imported there" (`doc/*/reference/functions/file-length.md`)
-- and `nil` is genuinely CL's answer for "cannot be determined", so a portable
caller is supposed to take its unknown-length fallback. In practice the gap is
sharper than that:

```lisp
(with-open-file (in "models/bunny.obj" :element-type '(unsigned-byte 8))
  (print (min 4096 (file-length in))))
;; interpreter / JVM: 4096
;; wasm preview 1 / component: wasm trap: cast failure
```

The failure is not a fallback taken, it is a TRAP one call later, in `min`. Any
loader written the ordinary way -- allocate `(file-length in)` bytes, or test a
format by its exact length -- runs on two backends and traps on two.

## Why it mattered, concretely

The classic binary-STL test is exact length arithmetic: a file is binary iff it
is exactly `84 + 50n` bytes for the `n` at offset 80. That test cannot be used,
so `geom::%stl-ascii-p` decides the dialect from the file's SHAPE instead (an
ASCII file opens with `solid` and carries `facet`/`endsolid` on its next line).
That is a good design for other reasons -- one code path, identical on all four
backends -- but it is a weaker test than the length one, and it was chosen
because of this gap rather than on its merits.

`read-sequence`'s short-fill return value covers "how much did I actually get"
and is identical on all four (verified 2026-08-30), so `%head-bytes` needs no
size. Nothing else in the repo depends on `file-length`.

## What the fix is

Import the size call and answer for real:

- **Preview 1**: `fd_filestat_get` (or `path_filestat_get`), whose `filestat`
  struct carries `size` at a fixed offset. The fd is already in hand -- the
  `_open` discipline and the `HEAP_PTR` chunk reservation that
  `WasmPackedIoRuntimeBuilder` uses are the pattern to copy
  (`.kb/binary-sequence-io.md`).
- **The component / WASI 0.3**: the filesystem world's own stat, through the
  same WIT lowering the other file operations take (`.kb/wit.md`).
- Keep `nil` for everything that genuinely has no length: a string stream, a
  socket, a standard stream, a closed handle.

Then update `doc/{en,ja}/reference/functions/file-length.md` (both trees say the
WASM backends always answer `nil`) and add a `ci-spec.yaml` case that writes a
file of a known size and reads its length back on all four.

Worth doing even though `geom` no longer needs it: `file-length` is a plain
ANSI operator, and "answers on two of four backends and traps the caller on the
other two" is the kind of gap a program discovers at run time on the backend it
was not developed on.
