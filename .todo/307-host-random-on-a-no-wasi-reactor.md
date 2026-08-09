# `--no-wasi`: let a host supply `random`, and stop dropping a user's `(defun random ...)`

Difficulty: Medium

Two independent gaps, found together while measuring whether a `--no-wasi`
module can be pointed at the host's entropy. Part A is the feature; part B is a
silent-wrong-behaviour bug that the same investigation exposed. They are in one
item because the obvious answer to A ("just redefine `random`") is B, and B is
not a good answer to A -- the reasoning for that is recorded below so it is not
re-litigated.

## Where things stand (measured 2026-08-09, node 24)

A `--no-wasi` module carries its own SplitMix64 generator behind `random_get`
and exports `__ronto_seed_random (i64)` so a host can replace its constant
start state before `_initialize` (`.kb/wasm-export-no-wasi.md`). That covers
"unpredictable per isolate". It does NOT cover:

- **per-call host entropy** -- the generator is still the module's;
- **`rontolisp:random-bytes`**, which stays a call-time error because
  SplitMix64 is invertible from one output and must not be passed off as a
  CSPRNG.

`rontolisp:wasm-import` already works under `--no-wasi` -- the module keeps zero
WASI imports and gains exactly the declared one:

```lisp
(rontolisp:wasm-import 'host-random :from "env" :params '() :returns :s32)
```
```
(import "env" "host-random" (func (;0;) (type 69)))
```

so the host CAN be reached. What is missing is a way to point `random` at it.

## Part A -- route the `random_get` slot at a host import

The natural spelling of "use the host's random" is not a Lisp redefinition, it
is: leave eight of the nine `--no-wasi` stubs alone and let `random_get` be a
real import again. Then `random` keeps its whole CL surface (integer limit,
bignum limit, float limit, the runtime type dispatch in `WasmRandomCompiler`),
a quickloaded library's `(random ...)` is served without the library knowing,
and the entropy is genuinely the host's -- which is what makes it sound to
un-gate `rontolisp::%random-byte` / `rontolisp:random-bytes` while it is in
effect.

Mechanics, which are small because the pieces exist:

- The nine stub bodies are the `if (this.noWasi)` loop in `WasmLispCompiler`'s
  code section. Slot `FUNC_RANDOM_GET` becomes a forwarding body
  (`local.get 0; local.get 1; call <import>`) instead of
  `buildNoWasiRandomGetBody()`.
- `am.ik.wasm.WasmImportInjector` already prepends import entries and remaps
  every `call`/`ref.func`, and already creates the import section from nothing
  under `--no-wasi` (`.kb/wasm-import.md`), so the import placement needs no new
  machinery.
- `WasmExprCompiler`'s `--no-wasi` guard on `RANDOM_BYTE_INTERNAL` becomes
  conditional on this being in effect.
- `__ronto_seed_random` should NOT be emitted when it is in effect (there is no
  module-local state left to seed); decide whether that is silent or an error if
  both are somehow requested.

Open sub-decisions, to settle before writing code:

1. **How it is spelled.** Either a `wasm-import` option
   (`:provides :random-get`, keeping the host's function name and signature
   under the user's control) or a CLI flag (`--host-random`, fixing the import
   as `env.random_get(ptr, len) -> errno`). The flag is less surface and gives
   every Worker the same one-line `index.js` addition; the directive is more
   honest about the fact that the module now has an import the host MUST
   provide. Lean flag, but write the reason down either way.
2. **The reactor component.** `--component --no-wasi` currently rejects
   `rontolisp:wasm-import` outright ("not supported with --component (Preview 1
   core modules only)"), and its zero-import property is stated as the flag's
   contract, not as a narrowing outcome. So either this is core-module-only
   (say so, the way `__ronto_seed_random` already is) or the component grows a
   lifted `wasi:random`-shaped import -- a WIT world-shape decision, not a core
   export decision. Whichever: the `.kb` line "a reactor component imports
   nothing" has to end up still true or explicitly amended.
3. **Zero imports stays the default.** The Cloudflare examples advertise
   `imports: zero`; this must be opt-in, and their READMEs should keep
   advertising the default while mentioning the opt-in.

## Part B -- the compile paths silently ignore a user's `(defun random ...)`

Independent of A, and a bug on its own. The interpreter honours a redefinition
of a CL built-in; both compile paths drop it without a word:

```lisp
(defun random (&rest args) (declare (ignore args)) 42)
(print (random 1000))
```

| redefined | interpreter | JVM | WASM |
| --- | --- | --- | --- |
| `random` | **42** | ignored | ignored |
| `sqrt` / `length` / `abs` / `gethash` | **42** | ignored | ignored |

So it is not about `random`: every compiler-intercepted built-in behaves this
way. `open` is the one exception, special-cased by
`compiler/NoWasiFilesystemStubs.definesOpen` so that a program defining its own
`open` is not rewritten.

CL itself says the consequences of defining a function on a `COMMON-LISP`
symbol are undefined (CLHS 11.1.2.1.2), so "the compilers ignore it" is not
non-conforming. The defect is that **three backends disagree silently**: the
same source computes different answers depending on where it runs, with no
diagnostic anywhere.

Two ways to close it, and they are not the same size:

- **Scoped**: apply the `definesOpen` trick to the intercepted operators one at
  a time, starting with `random` (~10 lines each in `WasmExprCompiler` and
  `JvmExprCompiler`). Fixes the surprise where it actually bites.
- **General**: make user redefinition of any intercepted built-in work on the
  compile paths. Interacts with the pure-builtin fold
  (`.kb/pure-builtin-fold.md` -- folding `(length '(1 2 3))` at compile time is
  wrong if the program redefines `length`), with inlining, and with the
  dispatch gate. Needs a policy decision first: honour it, or DIAGNOSE it (a
  build-time warning "this redefinition of a CL symbol is ignored on the
  compile backends") and keep the current behaviour. The diagnostic may well be
  the better answer -- it is conforming, it is cheap, and it removes the
  silence, which is the actual complaint.

**Why part B is not the answer to part A** (so this is not re-proposed): a
user-supplied `(defun random (n) ...)` also serves every LIBRARY call, and the
built-in it replaces is not a one-liner. The concrete case is smart-buffer's
`(random (expt 36 8))` -- limit 2,821,109,907,456. A hand-written
`(mod (host-random) n)` over a 31-bit host draw returns at most 2^31, so the
temporary-directory name would silently use 0.07% of the intended range, with
no error. Making library code correct through a user redefinition requires the
user's `random` to be as complete as the compiler's (bignum limits, float
limits, the runtime type dispatch), which is the wrong thing to ask.

## Done when

- A `--no-wasi` program can be built so that a quickloaded library's
  `(random ...)` reaches the host's generator, verified on V8 (node is enough)
  by counting host calls, not inferred -- and the zero-import default is
  unchanged, pinned by a test.
- `rontolisp:random-bytes` works when and only when the host really supplies
  the entropy, with the reason recorded next to the rule in
  `.kb/wasm-export-no-wasi.md` (that file's stub table and its "a stub may
  answer when the answer is true of this module" rule are the thing to keep
  honest).
- The `(defun random ...)` case either works or says why it does not, on all
  four backends, with the cross-backend result pinned.
- `doc/{en,ja}/guides/wasm-gc-module.md` (the No-WASI table + the seeding
  section) and the Cloudflare examples' Limitations sections follow.

## Related

`.kb/wasm-export-no-wasi.md` (the stub table, `__ronto_seed_random`, the rule),
`.kb/wasm-import.md` (the injector and the fixed-index invariant),
`.kb/pure-builtin-fold.md` (what part B's general form collides with),
`compiler/NoWasiFilesystemStubs` (the `definesOpen` precedent), `.todo/306`
(the clock, the other half of the same stub table).
