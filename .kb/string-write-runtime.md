# The string arm of an indexed write is ONE function, not an inlined rebuild

**Invariant: no `(setf (aref v i) x)` / `(setf (elt v i) x)` / `(setf (char s i) c)`
site emits the string rebuild inline. Every one of them calls
`%schar-set-runtime`, which the program carries at most once.**

## The lowering

`LispMacroExpander.expandSetf` gives a rank-1 indexed place a runtime string arm
(`.kb/adjustable-arrays.md` for why: a rank-1 array place may hold a string, and CL
says `(setf (aref s i) c)` on one is legal). Three place heads reach it -- `aref` /
`svref`, `elt`, and `char` / `schar` -- and all three funnel into `%schar-set`, which
the compile paths expand with `expandScharSetFunctional`:

```lisp
(let ((__schar_i i))
  (let ((__schar_c c))
    (setq v (%schar-set-runtime v __schar_i __schar_c))
    __schar_c))
```

`scharSetRuntimeDefun()` is the callee, and it answers **the string the write leaves
behind** -- the same object for a mutable character vector (written in place through
`%row-major-aset`), a fresh one for an immutable string (rebuilt around the replaced
character). That one answer is what lets a single call-site shape serve both arms; the
`setq` back into the variable is why the place must be a VARIABLE, the lite semantics
`.kb/adjustable-arrays.md` already documents.

**The rebuild uses `%subseq-core`, not `subseq`.** It runs only where `%arrayp` said
no, so the general-array copy arm that `expandSubseqCompat` wraps every plain `subseq`
in -- a `%array-alike` plus an inline `dotimes` copy loop -- is dead there. Skipping it
is the difference between a **7,187**-byte helper and a **665**-byte one.

**Injection**, `withScharSetRuntime` in `expandTopLevelDefinitions`, at both of its
exits (the same two `withFormatRenderer` uses, `.kb/format.md`). It has to be a scan of
the pre-expansion program -- expression expansion happens per form much later and
cannot add a top-level defun -- so it names the PLACE HEADS (`aref`/`svref`/`elt`/
`char`/`schar`/`%schar-set`, anywhere in the form, in any position) rather than trying
to predict which of them will keep the string arm. **Deliberately generous**:
over-injecting costs one unreachable defun, which `--optimize` drops and which is
byte-identical without it in every program measured; under-injecting would be a call to
a function that does not exist. The interpreter never sees any of this -- it does not
run `expandTopLevelDefinitions`, and its `%schar-set` is a real in-place primitive.

## Why it is a function

The rebuild is two `subseq`s, a `string` and two `%string-concat`s. `subseq` lowers to
an inline copy LOOP on both compile paths, so the arm was **~8 KB of wasm at every
site** -- and an array-only program paid it, because nothing in `(setf (aref m i) 0.0)`
tells the compiler `m` is not a string.

Measured on the wasm-GC backend at `--no-wasi --optimize`, one `(setf (aref m k) 1.0)`
site added to a `(make-array 16)` program: **8,615 -> 588 bytes**. On the JVM, one
`(setf (elt s i) v)` site: **5,042 -> 293 bytes**. `webgl-cube` is the extreme case --
25 sites across six `mat4-*` defuns, which held 203 of its 218 KB:

| program | flags | before | after | |
| --- | --- | ---: | ---: | ---: |
| `browser/webgl-cube/cube.lisp` | `--no-wasi --optimize` | 218,235 | 37,202 | **-83.0%** |
| `browser/webgl-platformer` | `--no-wasi --optimize` | 537,633 | 140,177 | -73.9% |
| `browser/webgl-galaxy` | `--no-wasi --optimize` | 57,148 | 25,620 | -55.2% |
| `browser/webgl-battlefront` | `--no-wasi --optimize` | 1,157,082 | 558,732 | -51.7% |
| `browser/webgl-robot-arm` | `--no-wasi --optimize` | 615,373 | 360,982 | -41.3% |
| `browser/hiragana` (`infer`) | `--optimize` | 1,263,046 | 1,232,436 | -2.4% |

**The crossover is one site.** A program with exactly one live site trades ~8 KB of
inline code for a ~665-byte function and comes out about even (`rainbow` +60 bytes, its
one site living in spliced library code). A program with no live site is unchanged, the
helper having been injected and then shaken out (`heat3d` +2 bytes of index-width
residue; `minesweeper`, `hello`, `greet`, `dice`, `triangle`, both `wasm-size` programs
byte-identical). Everything above two sites is pure win.

## The re-evaluation trigger

Two things would make this worth revisiting, and neither is "inline it back":

- **If `%schar-set-runtime` becomes hot.** The mutable-character-vector arm is now a
  call where it used to be an inline `%row-major-aset` -- one call per character
  written, in the `make-string` fill loops (ironclad's hex conversion is the shape).
  The answer would be a fast path at the SITE (a `ref.test` on the mutable-vector
  representation before the call), not a return to inlining the rebuild.
- **If `subseq` on a string ever becomes one call on both compile paths.** Then the
  `%subseq-core` spelling here stops mattering and the helper can go back to plain
  `subseq` -- but only then; today the difference is 10x the helper's size.

What is left in a site after this is `%aset` itself, ~515 bytes of inline
farray / packed-int-vector / general-array dispatch, which is the same shape of cost one
order of magnitude down. It stays inline on purpose: the packed-integer arm is the fused
raw-i64 store (`.kb/packed-integer-vectors.md`), which a call would give up.

Same lesson, different mechanism, as `.kb/wasm-shared-coercion.md` (a wasm runtime
function emitted by the backend) and `.kb/format.md`'s `%fixed-decimal` (a compiler
primitive): when a per-site expansion grows past a few hundred bytes, it becomes a
callee. This one is a spliced Lisp defun, so the JVM and both wasm-GC backends get it
from one definition.

## Pinning tests

- `LispMacroExpanderTest.aStringWriteSiteIsOneCallAndNotAnInlinedSubseqConcatRebuild` --
  the site names `%SCHAR-SET-RUNTIME` and none of `SUBSEQ` / `%STRING-CONCAT` /
  `%ARRAYP`. It fails the moment the rebuild comes back inline, and that failure is the
  measurement above coming back.
- `LispMacroExpanderTest.theStringWriteRuntimeIsInjectedForAnArrayPlaceAndOmittedWithoutOne`
  -- the gate, in both directions.
- The behavior itself is pinned where it already was: the `setf-elt-cross-backend`
  ci-spec case, `LispEvaluatorTest.evalSetfEltDispatchesOverListStringAndVector`,
  `JvmLispCompilerTest.compileSetfEltOnAStringMutatesIt`, and
  `WasmLispCompilerIntegrationTest.compileSetfEltDispatchesOverListStringAndVector`.
