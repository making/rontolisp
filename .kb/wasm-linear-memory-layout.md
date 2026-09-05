# The emitted WASM module's linear-memory layout

Every address a Preview 1 module writes must come from a region the PROGRAM sized, not from a
constant chosen when the compiler was written. Layout low to high (`WasmLispCompiler.compile`):

| region | base |
| --- | --- |
| low scratch cells | 0..255 — `HEAP_PTR_ADDR`=84, `TIME_SCRATCH_ADDR`=128, `ENV_COUNT_ADDR`=136, `RT_INTERN_BASE_ADDR`=152, `PATH_SKIP_ADDR`=248, `RANDOM_SEEDED_ADDR`=252 |
| static data (interned strings, then the three case-fold tables) | `DATA_BASE_OFFSET` (256); `COMPONENT_DATA_BASE_OFFSET` (0x60000) under `--component` |
| env/argv scratch, written by the HOST via `environ_get`/`args_get` | `scratchBase` = 16-aligned `staticEnd` |
| runtime intern table (`_intern`) | `rtInternBase` = `max(RT_INTERN_MIN_BASE, 16-aligned end of the block above)` |
| bump heap (every runtime string, grows upward) | `heapBase` = `rtInternBase + RT_INTERN_REGION_SIZE` |

- The env/argv block used to be four FIXED 16 KiB regions in page 3 (`ENV_PTRS_ADDR` 0x30000,
  `ENV_BUF_ADDR` 0x34000, `ARGV_COUNT_ADDR` 0x38000, `ARGV_BUF_ADDR` 0x3C000). A program with
  more than ~192 KB of interned strings grows across them and the host's writes land INSIDE the
  program's own static data.
- **That failure has no symptom of its own**: nothing traps, some unrelated constant reads back
  wrong, and WHICH one moves with the layout — a one-byte change anywhere moves or hides the
  damage. On the ci-spec corpus it landed in the `char-downcase` fold table and 335 of 474
  cases failed with `char-upcase` still perfect beside it.
- The fix is structural: the block sits just above the static data, the intern table and heap
  just above the BLOCK, so neither growth direction can reach it at any program size.
  `WasmArgvRuntimeBuilder.build` / `WasmGetenvRuntimeBuilder.build` add `SCRATCH_*_OFFSET` to
  that base, keeping the historical 16 KiB spacing and the 16 KiB ceiling on one environment
  (`.kb/time-environment-builtins.md`).
- The block is reserved ONLY for a program that can reach `%host-argv` (Preview 1 only) or
  `%host-getenv` off `--component`; anything else keeps `SCRATCH_UNUSED_BASE` (0x30000) and is
  emitted byte-for-byte as before, staying on the four-page floor
  `WasmLinearMemoryHeadroomTest` pins.
- Two regions are still fixed and still correct, both `--component`-only (static data starts at
  0x60000, so the block lands above them): `CABI_HP_CELL_ADDR` / `CABI_HP_BASE` (0x10000) and
  `SOCK_FD_ADDR` (0x40018).
- Trap when writing a test for this class: assert through the VARIABLE
  `uiop:*command-line-arguments*`, not `(uiop:command-line-arguments)` — the defvar seeds at
  LOAD time, and a later read sees GC-array copies the clobber can no longer reach.

## Tests
- `WasmLispCompilerIntegrationTest#preview1ArgvDoesNotCorruptStaticDataItGrewPast`, and its two
  ancestors one region lower, `#preview1GetenvDoesNotCorruptNewline` and
  `#preview1TimeDoesNotCorruptNilLiteral` (which are why `DATA_BASE_OFFSET` is 256, not 128)
- `WasmLinearMemoryHeadroomTest`
