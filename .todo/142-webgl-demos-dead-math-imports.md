# The WebGL demos' `math` imports are (mostly) dead, and their prose still sells them

**Status:** open, unstarted. Found 2026-07-17 while doing `.todo/132` (the WebGL demos'
gl.wit migration), which measured every demo's real import list for the first time in a
while. Nothing here is caused by that migration -- it is visible in the PRE-migration
build too -- and fixing it changes the `.wasm`, which is exactly what todo 132 had to hold
byte-identical. So it was left alone and written down here instead.

## What was measured

`sin` and `cos` are rontolisp BUILT-INS now (`.todo/109` Phase 2 added the transcendental
ufuncs). Every webgl demo still carries the directives it needed before that:

```lisp
(rontolisp:wasm-import 'sin :from "math" :params '(:float) :returns :float)
(rontolisp:wasm-import 'cos :from "math" :params '(:float) :returns :float)
```

but at the call site the built-in wins, so the import is unreachable and `--optimize`
shakes it. The `math` module each page hands to `WebAssembly.instantiate` is now partly or
wholly dead JavaScript:

| demo | `math.*` the module actually imports | the page provides |
|---|---|---|
| `webgl-triangle` | (none) | (no math module) |
| `webgl-cube` | **(none)** | `sin`, `cos` |
| `webgl-galaxy` | **(none)** | `sin`, `cos` |
| `webgl-heat3d` | `sin`, `cos` | `sin`, `cos` |
| `webgl-platformer` | `atan2` | `sin`, `cos`, `atan2` |
| `webgl-robot-arm` | `sin`, `cos`, `atan2`, `acos` | `sin`, `cos`, `atan2`, `acos` |

cube and galaxy compute their trigonometry in Lisp today and nobody noticed.

**Do not read heat3d/robot-arm's surviving `math.sin` as "those two still call out".** They
are the funcall-dispatcher conservatism (`.kb/wasm-import.md`): a program that takes
functions as values keeps same-arity import wrappers reachable, which is the same reason
heat3d imports a `disable`/`depthMask` it never calls. Whether any of them is ever CALLED
at run time is unverified -- check before deleting anything.

`atan2`/`acos` are the interesting ones: CL spells them `(atan y x)` and `(acos x)`, so
whether a built-in covers the demos' use is a real question, not a rename.

## Why it matters

1. **The prose sells a boundary that is gone.** "even `sin` and `cos` are borrowed from
   JavaScript's `Math`" was one of the nicer teaching points of the WebGL pages. Todo 132
   removed the claim from `webgl-cube` and `webgl-galaxy`'s pages (they provably import no
   `math` at all) and from `examples/README.md`'s galaxy row, but the DIRECTIVES are still
   in all five `.lisp` files, and the pages still hand over a `math` object.
2. **It is the same drift todo 132 set out to kill**, one module over. gl.wit made the `gl`
   module's two sides agree by construction; `math`, `canvas` and each demo's staging
   imports are still hand-written on both sides with nothing checking.

## The work

1. Decide per demo, per function, from the measurement -- not from the source reading.
   Delete a directive only after confirming the built-in covers the call (`atan2`/`acos`
   need this most).
2. Delete the matching page-side `math` entries. A demo that ends up importing no `math`
   at all should lose the module from its import object.
3. Re-verify each page in a browser (the trig is load-bearing: galaxy's spiral, robot-arm's
   IK), and re-check the numbers every doc quotes -- `.kb/wasm-import.md`,
   `doc/{en,ja}/compiling/wasm.md`, `examples/README.md` and each demo README carry an
   import count that this will move again.
4. Rebuild + commit the six `.wasm` (they are checked in and served by Pages).

## Definition of done

- No demo declares a host import its module does not import.
- No page provides a `math` entry nothing imports.
- Every count in the docs re-measured against the rebuilt modules, and no page claims a
  computation happens in JavaScript when it happens in Lisp.
