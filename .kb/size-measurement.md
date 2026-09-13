# Measuring size: declare which number you are minimizing

**Invariant: "smaller" is not one quantity. Raw bytes, compressed bytes, a section, a
total, and what an external optimizer can still recover are five different functions of the
same artifact, and they do not move together. A change that improves one can worsen
another, silently, and a comparison that quotes one is not a comparison of the other. State
which one a change is for BEFORE measuring it.**

This file is short on purpose: it names the rule and points at the three places where it
was learned. Each was learned the same way -- by optimizing the wrong number first.

## Raw bytes and compressed bytes move in opposite directions when bytes are RELOCATED

Removing a byte helps both. **Moving** a byte helps raw and hurts gzip: relocation
recovers only the per-function overhead (size prefix, locals vector, `end`, the `call`, the
function-section entry -- 6 to 12 bytes, flat) while the moved body itself stops being a
repetition the compressor was living on, at a cost proportional to its size.

Measured twice, independently, before anyone was looking for it:

- The single-call-site body mover: at an unlimited budget, **-50,722 raw and +16,064
  gzip** over 23 artifacts. `MAX_MOVED_BODY = 64` is where the small host-facing modules
  still collect and the compressed rows stop losing. Details, per-artifact worst rows and
  why 64: [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md),
  "Relocating bytes costs REPETITION".
- The duplicate-body fold, in the same file: identical bodies compress well, so on small
  clack Workers its raw win comes with a few hundred bytes MORE gzip.

**Which one is the target is a property of the deployment, not a preference.** A Cloudflare
Worker's platform limit is compressed bytes
([`size-report/notes/cloudflare-workers.md`](../size-report/notes/cloudflare-workers.md));
a file someone downloads once is raw. So: when a change MOVES bytes rather than deleting
them, measure gzip too, and say in the commit which number it is for.

## A total is not a section

A module's total is moved by decisions that are not about code generation at all -- chiefly
what the producer chose to export. A cross-toolchain comparison quoting totals can be won
by exporting less, which answers no question anybody asked. Read `code` first, then ask
what the other rows are paying for: [`size-report/notes/wasm-flags.md`](../size-report/notes/wasm-flags.md),
"A total is not a comparison", with the worked example.

## A residue is neither a ceiling nor a measure of what is left

What an external optimizer still finds over the shaken output tells you where to look and
nothing else. A lowering change walks straight past it (the `car`/`cdr` change moved the
hello-clack Worker 101 KB while binaryen kept finding the same 182,697 bytes); and a
residue near zero does not mean nothing is left (a module five bytes from what a
hand-written non-GC toolchain emits still carried 182 bytes of a string walked out of
linear memory and back). [optimize-dead-code-elimination.md](optimize-dead-code-elimination.md),
"What an external optimizer still finds".

## The shape they share

Each of the three is a number that LOOKS like "size" while being a different function of
the artifact, and in each case the number that was easiest to measure was not the one the
change was for. The rule is not "measure more"; it is **name the target number first**, so
that a result which improves a different one is visible as the trade it is.
