# A `-1` reshape extent declines the device copy the member documents

Difficulty: Low

`linalg:reshape`'s own defun says the spelling is supported -- "One extent may be -1 and is
inferred from the element count (numpy); a bare -1 shape flattens" -- and BOTH offer layers
refuse it:

- `eval/LinalgGpu.reshape` reads the shape through `LinalgSimd.shape`, which answers `null`
  for any extent `< 0`.
- `codegen/jvm/JvmGpuTemplate.gpuReshape` reads it through `shapeOf`, which does the same,
  and says so in its javadoc ("a `-1` shape declined").

So the two agree, which is why `GpuOfferDifferentialTest` has nothing to say about it; they
agree on refusing the spelling the member advertises. `--simd`'s own `LinalgSimd.reshape`
takes the same helper and declines it too, so a `-1` reshape runs the scalar defun at every
flag.

`linalg:reshape` is a RESIDENT-tier member: the decline is not "the CPU does it instead", it
is a resident array dragged home and copied element by element by the defun.

## The price, measured (todo-655's census, 2026-09-03, GB10)

`examples/deep-learning-from-scratch/ch07/train-convnet.lisp` is the program that produces
the spelling -- im2col reshapes the `(N C H W)` batch with `(list -1 ...)`, 80 declines a
run over resident `#d` arrays up to 432000 elements. The CEILING was taken by resolving the
`-1` against the operand's element count in both layers behind a system property and
running the same program, `--gpu --simd`, three walls each, the accuracy line identical:

| | declined (today) | resolved (the ceiling) |
|---|---|---|
| compiled, `-o Cv.class` | 1.76 / **1.81** / 1.83 s | 1.47 / **1.50** / 1.52 s (**-17.1%**) |
| interpreter, `java -jar` | 22.10 / **22.25** / 22.28 s | 16.45 / **16.55** / 16.65 s (**-25.6%**) |

The compiled figure is the one to beat, and it is a whole-run wall including ~0.5 s of JVM
start and dataset load, so the training loop's own share is larger than 17%.

## What the work is

Resolve a single `-1` extent against the operand's element count where the shape is read
FOR A RESHAPE, in both layers, and leave every other reader of `shape` / `shapeOf` alone --
`gather-strided`'s `od` and `transpose`'s axis list have no such spelling and a negative
extent there is still a decline. `LinalgSimd.reshape` should get the same treatment in the
same change, or `--simd` keeps running the defun on a shape `--gpu` now takes.

Pin it the way `.todo/654` pins the pair: a `GpuOfferDifferentialTest` row at a `-1` shape,
so the two layers cannot drift apart on it later.

## Where it came from

`.todo/655`, the sweep of every device accept rule against the shapes the programs actually
hand it. It is the one refusal in that census with a price; the census and the two refusals
that turned out to be free are in `.kb/gpu.md`, "The accept rules against the shapes the
programs run".
