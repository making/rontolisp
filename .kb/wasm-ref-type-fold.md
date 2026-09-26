# WASM GC: the type-test fold (`am.ik.wasm.WasmRefTypeFolder`) and call forwarding

**Invariant: a closed wasm-GC module's runtime type tests are decided by its own
constructors.** A `ref.test`/`ref.cast`/`ref.is_null` asks which heap types a value can
have, and a value can only be what a `struct.new`/`array.new` site in THIS module built,
an `i31`, or null -- so the set of types that can reach a test is a static fact, and a
test the set decides is a constant. `WasmRefTypeFolder.fold` computes those sets for
every reference-typed local, parameter, result, field, element, global and exception
payload, folds each decided test, prunes the arm an `if`/`br_if` can then never take,
turns a cast that can only fail into `unreachable`, and leaves the reachability shake to
drop what no live path calls any more. It runs on BYTES, in `WasmLispCompiler.shakeCore`
just before `WasmTreeShaker.shake`, at every level but `--optimize=off`, and renumbers
nothing -- so the shaker's segment and range claims still speak in the module's indices.

## The licence

**The module must be closed.** A wasm-GC struct is opaque to a host, so nothing outside can
forge a value of a defined type -- PROVIDED no reference crosses the boundary. The pass
declines (returns its input) when a function import or export, a global import or export,
or a tag import or export mentions a reference type, and throws on a table, an element
segment, `call_indirect`, `ref.func` or `any.convert_extern`. The backends satisfy it by
construction: every boundary value is a scalar or a linear-memory span, and a `$lisp-cond`
tag is never exported. **A new boundary that hands a reference in or out turns the pass
off for that module** -- silently, by design; the loss is size, never an answer.

## The analysis (optimistic least fixpoint)

- **Sets** are `BitSet`s over the type indices plus three pseudo-members (null, i31,
  other = funcref/exnref). A `struct.new T` answers the CANONICAL class of `T`
  (`Model.canonicalClasses`: partition refinement over `rec` groups from "all equal"), so
  a test on one of two canonically-equal indices answers for both -- byte-identical entries
  in different groups ARE the same type under wasm-GC, and folding one against the other
  would be the silent wrong answer.
- **Persistent sets** only grow: per-function locals (params joined at every live call
  site), per-function returns, per-(type, field) and per-array-type elements, globals
  (initializer walked too), tag payloads, loop parameters. A block's result sets are
  rebuilt by every walk and do not count toward the fixpoint (`Frame.persistent`) -- the
  first version counted them and never terminated.
- **A walk** is one structured pass over a body with an abstract operand stack
  (`Walk.step`): the exact arity of every instruction the backend emits
  (`WasmCodeModel`, the same finite subset `WasmSections.scanInstr` accepts), frames for
  block/loop/if/try_table with their carried values, definite assignment (an
  unassigned reference local reads as null), and dead-code skipping by jumping to the
  frame's `else`/`end` after a terminator. Liveness is part of it: a callee becomes live
  when a live `call` is walked, and the loop repeats until no set grew and nothing new
  became live. **A body is decoded when it first becomes live** (`Model.materialize`), with its
  per-body tables: the backends hand the pass their whole runtime, and decoding every body up
  front was half the pass's time (9% of `WasmLispCompilerIntegrationTest`'s compile CPU, JFR
  2026-09-24) for bodies no walk reached. A dead body is copied as it is, so the output is
  unchanged; the one difference is that an undecodable DEAD body no longer throws here (the
  shake's `scanInstr` still refuses an unknown opcode in any body).
- **A worklist, not full rounds.** A walk reads its own local/parameter/loop sets, its callees'
  return sets and the field/element/global/tag sets of the instructions it walks, so a function
  is walked again only when one of those grew since its last walk: `joinOwn` marks the function
  itself, `joinReturn` the callers recorded on earlier walks, `joinField`/`joinGlobal`/`joinTag`
  the functions recorded reading that type/global/tag (`Model.reads`); a global's growth also
  re-walks the global initializers. **A new read of a shared set must call `Model.reads`**, or
  its function misses the set's later growth. The least fixpoint of a monotone analysis does
  not depend on the order it is reached in, and each function's last walk saw the final sets
  (else it would be dirty again), so `targeted` and the rewrite are unchanged. Measured
  2026-09-24, analysis walks: a 3-line program's core 252 -> 93, the `ci-spec` corpus core
  32,507 -> 18,349 (most functions there read the cons fields, whose sets keep growing).
- **Bottom is dead code, not false.** A test on a value NO source produces marks the rest
  of the block unreachable. Folding it to 0 was the first version's bug: it selected the
  `else` arm of `_rat_add`'s "both exact integers" guard, counted the rational path's
  `_rat_new` as reachable, and the ratio type stayed inhabited for the very test that
  should have retired it.
- **Symbolic i32s** (`Sym`): a `Const`, or a `Bool(local, members, negated)` -- "local x
  as read at position p is one of S". `i32.or`/`i32.and`/`i32.eqz`/`i32.eq 0` combine two
  questions about the same local (no assignment between the two reads) into one, so
  `emitIsExactInt`'s `x is i31 | x is bignum | x is bigint` decides as ONE question. Every
  other i32 is opaque.
- **Refinement**: an `if` on a `Bool` narrows the local inside the arm it selects (and
  after an arm that never falls through; after a not-taken `br_if`; after a
  `br_on_cast`/`br_on_cast_fail` over a `local.get`, which leaves the local outside / inside the
  cast's type), only across a range the local is never assigned in -- so `_int_val`'s innermost `else` sees `x ∈ {bigint}`,
  its `ref.test bigint` folds to 1 and the `_type_err_int` landing goes.

## The rewrite

The final walk re-derives every decision from the fixed sets (a set that still grows there
just runs the loop again) and emits: a decided test's whole expression replaced by its
constant when the span is pure (`local.get`, tests, constants, `struct.new`; never a load,
a call, a trapping division), else kept and `drop`ped; a constant-conditioned `if` as its
live arm -- spliced in BARE when no branch targets the `if` (`Model.targeted`, recorded by
the previous walk), with every `br`/`br_if`/`br_table`/catch label crossing it counted one
fewer, or wrapped in a `block` of the same block type when one does; `br_if` on a constant
as `br` or nothing; an impossible `ref.cast` as `unreachable`. A `br_on_cast`/`br_on_cast_fail`
splits its operand's set: the part that passes the cast and the part that fails it go one to
the label, one to the fall-through (`Walk.stepCastBranch`). A `br_on_cast_fail` no value
fails is the `ref.cast` its fall-through is, one every value fails a `br` when the cast is
non-null (a nullable cast types the label's value non-null, which a `br` does not say, so it
stays and an `unreachable` follows); a `br_on_cast` that never branches goes when its cast is
non-null. **A block whose end is
unreachable gets an explicit `unreachable` after it**: a block's END is not
stack-polymorphic for the validator (`expected i64 but nothing on stack` was the symptom).
It is written unconditionally, because at that point the pass cannot see whether what
follows is the enclosing `end` or code that would stop validating without it; the peephole
below deletes it again wherever the answer is the first one.
Catch labels are relative to the try_table's ENCLOSING context, not its own label
(`wasm-tools print` shows `(catch 0 0 (;@2;))` for the enclosing block).

## What follows it

- **`WasmCallForwarding.redirect`** (same call, after the fold): a function whose whole
  body is `local.get 0..n-1; call g; end` with `g` of the same canonical type is a hop --
  every `call` of it is rewritten to `g`, chains to their last link -- and the shaker drops
  the stub. It exists because the fold leaves exactly such stubs of the dispatching
  helpers (`_rat_add` -> `_big_add` under `--optimize=size` only -- at `default` the
  i31 head keeps `_rat_add` a real function, .kb/wasm-int-fusion.md; `_rat_cmp` ->
  `_big_cmp`).
- **`WasmPeephole.rewrite`** (same call, behind the redirect): the adjacent-instruction
  peepholes, which also collect the fold's own `i32.const; drop` / `ref.null; drop` debris
  (`.kb/optimize-dead-code-elimination.md`, "The adjacent-instruction peepholes"). The
  fold's `unreachable`-after-a-block above is the one thing it must NOT undo blindly --
  it deletes such a trap only behind a loop that cannot terminate, in a block that leaves
  nothing.
- **The integer export lane** (`WasmExportCompiler.emitNarrowIntResult`, every integer
  result up to 32 bits): `if (i31 | bignum | bigint) _int_val else _as_f64; trunc; extend`
  into ONE i64 scratch, then `v != canon(v)` (wrap+extend, `extend8_s`, a mask) traps.
  Exact-or-trap is unchanged (a `TYPE_BIGINT` fits no 32-bit type; `_int_val`'s trap on it
  is the same refusal the trunc was); what changed is that the f64 lane is a branch the
  fold can prove dead, so an integer-only module no longer roots `_as_f64`,
  `_big_to_f64` and the float-to-decimal helpers.
- **A comparison in condition position is raw at every level**
  (`WasmComparisonCompiler.tryCompileConditionI32`): when fusion declines (or is off under
  `--optimize=size`), the generic `_rat_cmp_bits & mask` i32 feeds the `if`/`while`
  directly instead of boxing to `t`/`nil` for the consumer to test the box again. Not a
  speed-for-size trade -- it is smaller AND faster -- and in a module that never makes a
  string it stops `_t_sym`/`_str_build` being the only string machinery aboard.

## Measured 2026-09-12 (`--optimize=size`, `--no-wasi`; before -> after)

| Program | before | after | default level |
| --- | ---: | ---: | ---: |
| `.todo/790` `fib` + `:s32` export | 2,281 | **1,297** | 2,411 -> 1,537 |
| `.todo/789` reactor (two `:string` imports + `fib`) | 2,725 | **1,820** | 2,925 -> 2,060 |
| `pi_approx` (size-report) | 4,826 | **1,635** | -- -> 2,522 |
| `zlib` / chipz gunzip (size-report) | 125,738 | **94,167** | -- -> 117,618 |
| `hello_world` | 588 | 590 | 590 |

What `fib` still carries, and must: the limb tier (`_limb_new`/`_addsub`/`_cmp`/`_of`/`_get`,
~540 B) behind `_big_add`/`_big_sub`/`_big_cmp`, because `(+ (fib ...) (fib ...))` can
overflow i64 and the language promotes rather than wraps (`.kb/wasm-bignum.md`); `_int_new`
/`_int_val`; the wrapper's exact lane. The item's 639-byte spike was an i31-only `+` that
wrapped -- a measurement of the price of exactness, not a target. The fold decides
representation questions, never range ones.

## Traps

- **The analysis must stay a superset at every point.** A `Val`'s set is never mutated;
  joins go into persistent targets only; `local.get` clones. Refinements are applied at
  lookup (`effectiveSet`) from the base set, never stored -- a stored one goes stale when a
  recursive call widens the function's own parameter mid-walk.
- **`ref.test` folds, `ref.cast` narrows**: a cast's result set is the intersection, so a
  later test on the cast value still decides; an empty intersection is the trap.
- **Do not fold on emptiness in either direction** -- it is unreachable, see above.
- An `if` arm that ends in `br` to the `if`'s own label still needs the label: `targeted`
  is why the splice is decided from the previous walk, not at the opener.
- The corpus guard is `WasmTreeShakerCorpusTest` (decode-completeness + `wasm-tools
  validate` over `ci-spec.yaml` at `--optimize`): a newly emitted opcode throws in
  `WasmCodeModel.decode` there before it can be mis-framed.
- `-Drontolisp.wasm.debug-core=<path>` writes the core module as `shakeCore` receives it
  (host imports injected, nothing folded) plus `<path>.claims.txt`, the segment and range
  claims handed to the shaker -- what it takes to replay `fold` / `redirect` / `shake` by
  hand (jshell over `target/classes`) when a program's optimized output goes wrong. That is
  how the fun-name claim above was found: the fold and the shake each held alone, and only
  the claims replayed against the folded bytes showed which range cut a cited string.

## Tests

`WasmRefTypeFolderTest` (hand-assembled modules read back instruction by instruction, run
under wasmtime: the one-question merge, the undecidable half kept, the arm refinement, the
bare splice with reindexed branches, the targeted arm keeping its block, the always-failing
cast, the cast branch never taken / always taken / undecided with its refinement / reindexed
across a spliced arm, the declined boundary, the reactor's float tests gone and the fold's idempotence),
`WasmCallForwardingTest`, `WasmLispCompilerIntegrationTest.theNarrowIntegerBoundaryCrossesEveryTierExactlyAtEveryLevel`
and the mixed-tier programs in `.optimizedModulesPrintExactlyWhatTheUnoptimizedOnesDo`
(now at DEFAULT and SIZE), `WasmImportCompilerTest.twoMemoryTypedParamsStageOnDistinctRegions`
(whose probe had to hand the `:int` parameter an integer -- a string there is a type error
the fold proves, and the stagings after it are dead code, correctly).
