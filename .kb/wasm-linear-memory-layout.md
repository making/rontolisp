# The emitted WASM module's linear-memory layout

Every address a Preview 1 module writes must come from a region the PROGRAM sized, not
from a constant chosen when the compiler was written. The layout, low to high
(`WasmLispCompiler.compile`):

| region | base | who writes it |
| --- | --- | --- |
| low scratch cells | 0..255 | `HEAP_PTR_ADDR`=84, `RT_INTERN_BASE_ADDR`=152, `TIME_SCRATCH_ADDR`=128, `ENV_COUNT_ADDR`=136, `PATH_SKIP_ADDR`=248, `RANDOM_SEEDED_ADDR`=252 -- fixed, and `DATA_BASE_OFFSET` (256) exists to clear them |
| static data | `DATA_BASE_OFFSET` (256); `COMPONENT_DATA_BASE_OFFSET` (0x60000) under `--component` | interned strings, then the three case-fold tables (`WasmCaseFoldRuntimeBuilder`), each its own data segment |
| env/argv scratch | `scratchBase` = 16-aligned `staticEnd` | the HOST, through `environ_get` / `args_get` |
| runtime intern table | `rtInternBase` = `max(RT_INTERN_MIN_BASE, 16-aligned end of the block above)` | `_intern`, for symbols first seen at run time |
| bump heap | `heapBase` = `rtInternBase + RT_INTERN_REGION_SIZE` | every runtime string; grows upward, `memory.grow` past the declared minimum |

The env/argv block is the one that moved (2026-09-04). It used to be four FIXED 16 KiB
regions in page 3 -- `ENV_PTRS_ADDR` 0x30000, `ENV_BUF_ADDR` 0x34000, `ARGV_COUNT_ADDR`
0x38000, `ARGV_BUF_ADDR` 0x3C000 -- on the assumption that a module's static data ends
below page 3. A program with more than ~192 KB of interned strings grows straight across
them, and then the host's writes land INSIDE the program's own static data.

**The failure has no symptom of its own.** Nothing traps and nothing is reported: some
unrelated constant reads back wrong, and WHICH one moves with the layout, so a one-byte
change anywhere in the program (the corpus program's own PATH is a static string, so the
directory it is compiled in was enough) moves the damage or hides it. On the `ci-spec`
corpus it landed in the `char-downcase` fold table: `char-downcase` folded nothing, so
`string-downcase` answered its input, `char-equal` degraded to `char=`, and `format`'s
directive dispatch -- which downcases the directive character -- stopped recognizing
`~A`/`~S`/`~D` and printed every control string verbatim. 335 of 474 corpus cases failed
on the WASM leg with `char-upcase` still perfect beside it, because the UPPER table
happened to end 236 bytes below `ARGV_COUNT_ADDR`.

The fix is structural rather than a bigger constant: the block is placed just above the
static data and the intern table and heap just above the BLOCK, so neither the data
(which grows up from `DATA_BASE_OFFSET`) nor the bump heap (which grows up from
`heapBase`) can reach it, at any program size. `WasmArgvRuntimeBuilder.build` and
`WasmGetenvRuntimeBuilder.build` take that base and add `SCRATCH_*_OFFSET` to it; the
offsets keep the historical 16 KiB spacing, so the 16 KiB ceiling on one environment
(`.kb/time-environment-builtins.md`) is unchanged.

The block is reserved ONLY for a program that can reach one of the two host calls --
`%host-argv` (Preview 1 only; `--component` binds `wasi:cli/environment`'s
`get-arguments` instead) or `%host-getenv` off `--component`. Anything else keeps
`SCRATCH_UNUSED_BASE` (0x30000, where the block always was) in the unreachable helper
body and is emitted byte-for-byte as before, which is what leaves a small program on the
four-page floor `WasmLinearMemoryHeadroomTest` pins rather than adding a page to every
module.

Pinned by `WasmLispCompilerIntegrationTest#preview1ArgvDoesNotCorruptStaticDataItGrewPast`
(a 2800-string blob spanning all three old argv cells, plus a `uiop:*command-line-arguments*`
read), beside its two ancestors for the same bug class one region lower --
`#preview1GetenvDoesNotCorruptNewline` and `#preview1TimeDoesNotCorruptNilLiteral`, which
are why `DATA_BASE_OFFSET` is 256 and not 128.

**Why the variable and not the accessor in that test**: the `uiop/image` defvar seeds
`uiop:*command-line-arguments*` at LOAD time, before the blob's own strings are
materialized out of linear memory into GC arrays. A read through
`(uiop:command-line-arguments)` later in the program sees copies the clobber can no
longer reach and shows nothing -- which is exactly why the corpus failure surfaced in the
case-fold TABLE, the one structure read out of linear memory on every call rather than
copied once.

Two regions are still fixed and still correct, both `--component`-only, where the static
data starts at 0x60000 and the block therefore lands above it: `CABI_HP_CELL_ADDR` /
`CABI_HP_BASE` (0x10000, the serve memory module's canonical-ABI bump cell) and
`SOCK_FD_ADDR` (0x40018).
