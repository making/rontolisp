# No binary stdin/stdout, so the zlib size-report row is not the upstream program

Difficulty: Medium

## Symptom

`read-byte` / `write-byte` take a stream HANDLE from `open`, so there is no way
to move raw bytes over standard input or standard output:

```lisp
(read-byte 0)                 ; interpreter: "read-byte expects a binary input stream"
(write-byte 65 t)             ; same -- t is not a handle
```

Text works (`read-line`, `princ`), but it is not byte-transparent: the
interpreter and the JVM go through a `Reader`/`Writer` that UTF-8-encodes, and
WASM's `read-char` reads one raw byte, so a program pushing octets through
`code-char` would diverge across backends (`.todo/153` is the non-ASCII
`code-char` half of that).

## What it costs, concretely

`size-report/programs/zlib/zlib.lisp` decompresses gzip with chipz, which IS
what [wado-lang/wado's `wasm-size/zlib`](https://github.com/wado-lang/wado/tree/main/wasm-size)
does -- but not the same program:

| | upstream (c / rust / zig / wado) | rontolisp today |
| --- | --- | --- |
| input | read all of stdin | 507-byte literal embedded in the source |
| output | `fwrite` the decompressed bytes to stdout | print the length + an FNV-1a |

Measured 2026-08-10: dropping the FNV-1a and the `format` for a bare
`(princ (length raw))` saves only **2,008 bytes of 432,134** -- chipz's own
condition `:report`s already pull the format renderer in -- so the summary line
is NOT what makes the row incomparable. The stdin read and the stdout write are.

## The work

The pieces exist per backend; what is missing is a stream DESIGNATOR for the
standard streams:

- WASM Preview 1: `_read_byte`/`_write_byte` already move a byte through fd 0 /
  fd 1 via `BYTE_SCRATCH_ADDR` (`.kb/read-load-streams.md`), so the handles are
  already right -- only the designator lowering is new.
- Interpreter: the `streams` handle table has no entry for the process streams,
  and `read-byte` casts a table entry to `InputStream`. Seeding handles for
  `System.in` / a raw `System.out` is the change, plus a flush discipline so
  interleaving `princ` (buffered `Writer`) and `write-byte` (raw stream) does
  not reorder output.
- JVM: `_readByte`/`_writeByte` need the same two handles.
- `--component`: stdin already has an async path (`stdin.lisp` over
  `wasi:cli/stdin@0.3.0`) and stdout is `wasi:cli/stdout@0.3.0`; both are byte
  interfaces.

Decide the SPELLING first -- it is user-visible and hard to change later.
`(read-byte *standard-input*)` reads best and is what CL says (a bivalent
standard stream), but it means giving the standard-stream variables a handle
value rather than `t`; accepting `t` as the designator (what every other
rontolisp stream op already does) is the smaller blast radius.

## Deliverable

`size-report/programs/zlib/zlib.lisp` becomes stdin -> inflate -> stdout, the
`wasm_builds` row pipes the gzip stream in, and the "input/output" rows of the
table above disappear from `size-report/notes/wasm-flags.md`. Useful well beyond
this row: it is what any byte-oriented filter needs.
