# WASM: `%make-directories` / `%delete-file` are call-time errors on both backends

Difficulty: Medium -- two more preview1 imports plus their two adapter
implementations, following the `fd_readdir` precedent step for step. No new
mechanism; the cost is the import-surface churn and getting both component
adapters in step. Recommend starting with an Opus-class model.

Split out of `.todo/231` (2026-08-04). Decided as a deliberate divergence when
`.todo/225` landed (2026-08-01) and recorded in `.kb/read-load-streams.md` with
the "tenth import" re-evaluation trigger; this file exists because that trigger
has now FIRED -- the gap blocks real features rather than a hypothetical one.

## The gap

`ensure-directories-exist` and `delete-file` are prelude Lisp over ONE creating /
deleting primitive each (`%make-directories`, `%delete-file`), and only the
primitive is per-backend. Interpreter and JVM do the real thing; both WASM
backends lower to a call-time error
(`LispMacroExpander.makeDirectoriesStub()` / `deleteFileStub()`), because the
WASI import set carries no mkdir and no unlink call.

The signalling itself is CORRECT and must stay: unlike `file-length`, whose
contract has a "cannot be determined" answer, these two have none -- the
directory exists afterwards or it does not. What is missing is the ability to
answer YES.

## What it blocks today

- **smart-buffer's disk-spill path** (`.kb/lack.md`): a multipart body past
  `*default-memory-limit*` (1 MB) is written to a temporary file whose directory
  `%temp-file-name` creates. `LackEcosystemE2eTest` pins the WASM legs to the
  `ensure-directories-exist` error precisely because of this. Large uploads
  therefore do not work on either WASM backend.
- **mito's `generate-migrations`** (`.kb/mito.md`): deleting a superseded
  migration file is an interpreter/JVM-only branch of an operation that
  otherwise runs everywhere.
- **`uiop:with-temporary-file` in general** -- the whole temporary-file family
  reduces to these two primitives.

## Preview 1

`wasi_snapshot_preview1` already declares both calls, so this half is a plain
import addition:

- `path_create_directory(fd, path_ptr, path_len) -> errno`
- `path_unlink_file(fd, path_ptr, path_len) -> errno`

Both take the preopened dir fd 3 and a path staged into linear memory exactly as
`_open` / `_probe_file` stage theirs (see `WasmIoRuntimeBuilder.buildOpenBody`
for the HEAP_PTR advance-and-pop dance -- it is load-bearing under `--component`
and the new bodies must copy it). `%delete-file`'s contract is "nil when the file
is not there or the host refused", so a nonzero errno is simply `nil` -- no
signalling in the primitive.

**Import surface**: `IMPORT_FUNC_COUNT` goes 9 -> 11 and `FUNC_START` with it, so
**every emitted WASM function index shifts**. That is inherent, not a regression
(`fd_readdir` did the same going 8 -> 9). Keep the three consequences in step:

- `--no-wasi` defines two more trap stubs (same type indexes as the imports);
- `adapter.wat` exports both (component mode, below);
- `adapter-http-server-p1.wat` exports them too, as a nonzero errno -- the serve
  world has no filesystem, and both call sites read a nonzero errno as "no".

Append the new types AFTER the last fixed type the way `TYPE_FD_READDIR` was, so
the conditional `--simd` / async / instance blocks follow and no existing type
index moves. NOTE: both new imports have the SAME type as an existing one
(`(i32 i32 i32) -> i32`), so it may be possible to reuse it -- check before
adding types.

## WASI 0.3 / `--component`

No host import is added on this leg: the core module imports the two preview1
names from `adapter.wat` as always, and the adapter implements them over
`wasi:filesystem@0.3.0`. Both members already exist on the `descriptor` resource
(`src/wasm-component/deps/filesystem/types.wit`):

```wit
create-directory-at: async func(path: string) -> result<_, error-code>;
unlink-file-at:      async func(path: string) -> result<_, error-code>;
```

They are STRICTLY easier than `open-at`, which the adapter already drives: same
async shape, but the result carries no `descriptor` resource -- only the
error-code discriminant -- so there is no handle to allocate, register or drop.
The adapter's existing "lower a string argument + await a
`result<_, error-code>`" pattern (`$path_open`'s prologue plus the `sync` shape)
is the template.

Watch out for the trap the directory listing hit: the adapter must not assume
descriptor handle 0 is usable (see the `open-at` comment at `adapter.wat:141`).

## Acceptance

- `ensure-directories-exist` and `delete-file` run for real on both WASM
  backends, and the ci-spec gains a case creating a directory, writing a file in
  it, deleting it and probing that it is gone -- green on all four backends
  against the native binary.
- `LackEcosystemE2eTest`'s WASM legs move from
  `SUBSTRATE_EXPECTED_NO_FILESYSTEM` to the spilling expectation, and the
  test's javadoc loses the divergence clause.
- `.kb/read-load-streams.md` and `.kb/directory-listing.md` record the new
  import count and drop the two "call-time error" rows; `.kb/lack.md` and
  `.kb/mito.md` lose their interpreter/JVM-only qualifiers.
