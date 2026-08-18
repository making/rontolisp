# 436. CL gaps found by running upstream ASDF on the interpreter

Difficulty: High

Parent of `.todo/437` -- `.todo/443`. Each child is one landable change with its
own tests; this file carries the origin, the policy, the ordering and the
hazards that are shared between them. Read it before picking up a child.

## Where these came from

A spike (2026-08-18) ran upstream ASDF 3.3.7 -- the 14,130-line single-file
release -- on the interpreter to decide whether the hand-written mini-ASDF could
be replaced by the real thing. It cannot, and should not be
(`.kb/asdf.md`, "Why this is a shim and not real ASDF", carries the measurement
and the re-evaluation trigger). **These children are the by-product**: plain
Common Lisp defects and holes that upstream tripped over, none of them
ASDF-specific. Any real library can hit them, and several explain shim work we
keep redoing.

**They are NOT an ASDF-migration plan.** Nothing here brings real ASDF closer;
the shim stays and is widened per library as before.

## Policy: fill these PROACTIVELY, not on demand (decision 2026-08-18)

Waiting for a library to trip over one is how they were found in the first
place: each surfaces as a confusing failure inside somebody else's code, days
after the feature that needed it was written, and the shim absorbs the blame.
They are also cheap relative to that -- the list is closed, and upstream ASDF is
a ready-made exerciser for all of it.

## The children

| todo | what | items |
| --- | --- | --- |
| ~~`.todo/437`~~ | `print-object` is not dispatched for a NESTED object | bug -- LANDED 2026-08-18 |
| ~~`.todo/438`~~ | hash tables key by the printed form | bug -- LANDED 2026-08-18 |
| ~~`.todo/439`~~ | `with-open-file` / `open` / `load` reject computed keywords | surface -- LANDED 2026-08-18 |
| ~~`.todo/440`~~ | string designators | surface -- LANDED 2026-08-18 |
| ~~`.todo/441`~~ | wild pathname components | surface -- LANDED 2026-08-18 |
| ~~`.todo/442`~~ | the CLOS surface (`reinitialize-instance` &c) | surface -- LANDED 2026-08-18 |
| ~~`.todo/443`~~ | the missing standard names | surface -- LANDED 2026-08-18 |

`437` and `438` were real BUGS -- a library hitting them is silently wrong or
does not terminate. The rest is missing surface.

## Ordering

- **Wave 1, parallel: `437`, `439`, `440`, `441`, `442`.** Independent of each
  other. All five are done; the printer now walks a list /
  general rank-1 vector, so `438` is unblocked.
- **Wave 2: `443`.** Done. It added the most names, so the shared counter below
  moved with it: `list-functions` 430 -> 436, and the pinned `list-macros` STRING
  gained `DO-SYMBOLS` and `WITH-COMPILATION-UNIT`.
- **Wave 3: `438`.** Done. A table now places a key by a depth-capped structural
  hash and decides it with `equal` on every backend, so a cyclic key stores and
  retrieves (`.kb/hash-tables.md`); the deferred `:test` half is `.todo/444`.

`.todo/445` came OUT of `437` rather than out of the spike: a `defmethod
print-object` below its first use crashes the JVM backend, and it predates this
family.

`.todo/447` came OUT of `441` the same way `445` came out of `437`:
`translate-pathname` substitutes its captures positionally rather than component
by component, which `441` measured against SBCL but did not set out to close.

`.todo/444` is the deferred half of `438`: making `:test` real, and the `eq`
divergence underneath it. It is NOT in this family's wave -- it has a measured
"act when" trigger of its own.

## Shared hazards -- read before touching anything

- **The `list-functions` counter is a cross-cutting pin.** Adding a name to
  `PackageRegistry.CL_FUNCTIONS` moves the count, which is pinned in
  `ci-spec.yaml` AND `LispEvaluatorTest` AND `JvmLispCompilerTest` (two places)
  AND `WasmLispCompilerIntegrationTest` (`.kb/packages.md`). Two children
  landing names at once will collide there; rebase and re-count before pushing.
- **`LispNames.java`, `PackageRegistry.java` and `ci-spec.yaml` are touched by
  several children.** Append ci-spec cases at the END of the file under a name
  derived from the child's number, so two children do not insert at one spot.
- **Work in a separate git worktree per child** if they run concurrently --
  one checkout cannot serve two of them.
- Every child lands on **all four backends** with the usual ladder
  (interpreter -> JVM -> WASM -> ci-spec) unless its file says otherwise.
- A name being in `PackageRegistry.CL_SYMBOLS` is **not** evidence it is
  implemented. `442`'s probe (2026-08-18) closed `reinitialize-instance` /
  `shared-initialize` and found `print-object` to be the one remaining
  registered-and-absent `CL_FUNCTIONS` name (recorded in `443`). Grep before
  trusting the set.

## Re-running the finder

`.kb/asdf.md`'s reproduction recipe is the cheapest way to find the NEXT gap
after one lands: the loader is an ordered work list of what actually blocks.
Note that the interpreter expands macros lazily, so a `with-open-file` /
`change-class` error surfaces at CALL time, not at load -- a file that loads
clean is not clean.

Close this parent when the last child is gone.
