# GPU Acceleration (`--gpu`)

`--gpu` routes [`linalg`](linear-algebra.md)'s matrix product, its element-wise transcendentals and its broadcast / axis-fold / axes-transpose shapes to an NVIDIA device, driven straight through the CUDA driver. It is one of three orthogonal acceleration flags: [`--simd`](simd-acceleration.md) lowers the vectorizable `vec:` and `linalg:` kernels to CPU vector instructions, [`--blas`](blas-acceleration.md) replaces the matrix product with a tuned library call, and `--gpu` moves the work off the CPU entirely. Any combination of the three, or none -- [How the three flags compose](#how-the-three-flags-compose) covers what happens when more than one is on.

[`--blas`](blas-acceleration.md) puts the matrix product on the fastest thing the CPU has. `--gpu` puts it on a different machine altogether.

```bash
rontolisp prog.lisp --gpu                 # interpreter
rontolisp prog.lisp -o Prog.class --gpu   # JVM class output
rontolisp prog.lisp --simd --blas --gpu   # all three, chained; the device is asked first
```

**A GPU is recommended, never required**, exactly as a tuned BLAS is. Nothing is bundled and nothing is downloaded, and there is no CUDA toolkit to install: `libcuda.so.1`, which ships with the NVIDIA driver, is the entire runtime requirement, and the kernels travel inside rontolisp as a text that the driver compiles for whatever card it finds. A machine with no device, no driver, or a card older than Turing (compute capability 7.5) runs the same programs to the same output, only slower, and the interpreter says so on standard error rather than failing.

## What is accelerated, and what declines

**The matrix product, in both of its shapes.** `linalg:dot` over two rank-2 arrays -- and therefore `linalg:matmul` at rank 2 and `linalg:solve`, which are written over it. And the **stacked product** behind `linalg:matmul` at rank 3 or more, which is `torch.bmm`: every attention layer, and every `torch:linear` over a `(B T C)` activation. A stack costs one round trip and one launch however many matrices are in it, because the device carries the batch on an axis of its own; an operand that broadcasts over the batch -- the rank-2 weight matrix under a rank-3 activation -- is copied to the device once rather than once per matrix.

**And the twelve element-wise transcendentals**: `exp`, `log`, `tanh`, `sin`, `cos`, `tan`, `asin`, `acos`, `atan`, `sinh`, `cosh` and [`erf`](../reference/functions/linalg-erf.md) -- so `torch:gelu`, `torch:softmax` and `torch:log-softmax`, which are written over them, reach the device too. These are the members with the highest ratio in the whole flag, not the matrix product: `linalg:erf` over 1.5 M double-floats is 103 ms on a SIMD CPU and 0.9 ms on the device.

**And ten more members, each at ONE call shape.** `add`, `sub`, `mul`, `div`, `maximum` and `minimum` when their two operands have DIFFERENT shapes and numpy broadcasts them -- `(4 256 256)` against `(4 256 1)`, an array against its own per-row reduction, which is what `torch:softmax` and `torch:layer-norm` are built from; `sum`, `amax` and `amin` in their `:axis` form; and `transpose` with an axes list. `mean`, `var`, `std`, `linalg:softmax` and `linalg:log-softmax` reach the device through those, exactly as they reach the lane kernels on the CPU. What these shapes have in common is not their arithmetic: it is that the CPU walks them one element at a time with an index odometer rather than in vector lanes, so the CPU cost they have to beat is five to eight times the cost of the same operation on two equally shaped arrays. Measured on the JVM class output at a transformer's own shapes, single-float: a broadcast `sub` over 393216 elements is 660 us on the CPU and 118 on the device, `sum :axis` is 297 against 70, an axes `transpose` 335 against 75, and a whole `linalg:softmax` -- five of these members chained, five round trips -- 1915 against 402.

**The same names at an EQUAL shape are refused, and refused by measurement.** `sqrt`, `abs`, `negative` and `sign` stay on the CPU at every size, and so do `add`, `sub`, `mul` and `div` whenever both operands have the same shape. There the CPU runs a vector lane loop, so its cost is already just the cost of walking the array -- and a device has to walk it twice, over a link slower than memory, before it can start. Measured over 1.5 M elements, `linalg:sqrt` is 700 us on the CPU against 502 on the device (and 500 against 245 at single width, the flag's best case for it), while `linalg:add` is 900 us against 780 -- and at single width the CPU **wins**, 350 us against 382. A same-shaped `sub` over 393216 single-floats is 85 us on the CPU against 112 on the device. A member that wins by less than the measurement's own noise is not a member.

Everything else declines and runs exactly what it ran before -- the tuned library when `--blas` is on too, the lane kernel when `--simd` is, the portable `linalg.lisp` definition otherwise. That includes the two matrix-by-vector shapes `--blas` does take (they are memory-bound, so the trip cannot pay for itself), a rank-1 operand on either side of a stacked product, a batch shape whose slabs no single stride can reach (a broadcast axis sitting under a non-broadcast one), general boxed arrays, mixed widths, a scalar operand, and a shape mismatch, which signals the same error as ever.

It also includes **everything small**, and there are two thresholds because there are two kinds of work. A round trip to a device costs about 15 microseconds however little data rides on it, so a product below roughly 51x51x51 (`n * m * p` under 131072) declines and stays on the CPU; for a stack the same threshold applies to the TOTAL work, `batch * n * m * p`, because the round trip is paid once for the whole stack rather than once per matrix. An element-wise call is measured in **elements** instead -- one library call each -- and declines below **16384** of them; a broadcast or an axes transpose declines below **32768** result elements, and an axis fold below **131072** input elements or 256 output slices (a fold with one output slice is a single-threaded loop on a device, and loses to any CPU). Every threshold is one more decline rather than a mechanism of its own, which is why every example in this repository, all of which run shapes far below them, prints byte-identical output with the flag and without it.

## How the three flags compose

Each flag adds one attempt in front of the others, and every attempt that declines hands the same arguments to the next:

```text
--gpu --blas --simd   ->   device -> library gemm -> lane kernel -> portable definition
--gpu --simd          ->   device ->                 lane kernel -> portable definition
--gpu                 ->   device ->                                portable definition
```

`--blas` takes only the rank-2 product, so a stacked one -- or an element-wise call -- has no library rung at all: `--gpu --blas --simd` chains those device -> lane kernel -> portable definition. The device is asked first because its size threshold is three orders of magnitude above the tuned library's: it turns down everything small before touching the driver at all, and from about n=256 up it is ahead of a threaded CPU BLAS at both widths. So what the device declines lands on the fastest CPU path the invocation asked for, never back on the portable definition. The exception is a narrow band just above the threshold -- roughly n=64 to n=96 -- where `--gpu --blas` together accept a product the library alone would have finished sooner. Both sides are far under a millisecond there, and asking the library first instead would give away the several-fold win at the sizes the flag exists for.

## Reach and precision

`--gpu` reaches the **interpreter** (including the native binary) and the **JVM class output**. The CUDA driver is called through the foreign function API, which WASM does not have, so `--gpu` with a `.wasm` output is an error rather than a silent no-op; a WASM program has `--simd`.

A class compiled with `--gpu` is still standalone -- the whole CUDA binding travels inside it, so there is nothing to put on the classpath and `java Prog` is the whole command. It does call a restricted method, so run it as `java --enable-native-access=ALL-UNNAMED Prog` to keep the JVM's warning off standard error. In the native **binary** each device call currently costs 20 to 50 times more than on the JVM (one n=512 double-float product measured 17.4 ms against 0.74), enough that on that build `--gpu --blas` is slower than `--blas` alone at every size measured; `--gpu` still beats `--simd` there by more than 2x, and the portable definition by four orders of magnitude. Compiling the program to a class is the way around that cost -- the class the native binary emits is the one `java -jar` emits, and it runs at the speeds in the second table below.

**`--gpu` is the first flag whose results you should not expect to match the other backends digit for digit.** Two separate reasons, and the second is the new one:

- An accelerated **product** is close to the portable definition rather than equal to it. The device kernel folds each output cell in the portable definition's own order, but it fuses every multiply and add into a single instruction, so each term is rounded once where the portable definition rounds twice. Over inputs that are exact at the operand width (integers, powers of two) that cannot show and the results match exactly; over inexact ones they differ -- measured on an NVIDIA GB10 over operands of magnitude 1, by up to 5e-15 at `#d` and 3e-6 at `#f`.
- An accelerated **transcendental** has no such exempt class of inputs, because the device carries its own implementation of `exp`, `erf` and the rest. Two correct libraries disagree in their last digits and neither is wrong. At `#f` there is a second cause on top: the device evaluates at the operand width, where every CPU kernel here evaluates in double and narrows only on the store. Measured across each member's own range on the same machine, the worst relative difference from the portable definition is **2e-16 to 1e-15 at `#d`** (one to five units in the last place) and **1.1e-7 to 1.7e-7 at `#f`** (one to two). `erf` is the largest at `#d`, and that is on rontolisp's side rather than the device's: the portable definition is a series expansion, not a correctly rounded `erf`. One difference is visible rather than microscopic: an accelerated `erf` of a negative zero prints `-0.0` where the portable definition prints `0.0`.

- The **broadcast**, **axis fold** and **axes transpose** members are the exception: they stay byte-for-byte identical to the portable definition at both widths. Their kernels read every element widened to double, compute in double and narrow only on the store, which is the portable definition's own rule, and there is no library function anywhere in them for two implementations to disagree about. A program whose accelerated calls are only those prints exactly what it prints without the flag.

So a program that sums a million accelerated `erf` values will print a slightly different number with the flag on -- and a training run will diverge from the CPU one after enough steps, exactly as it would between two GPUs. The portable definition remains the cross-backend oracle, and `--gpu` is deliberately absent from the cross-backend test suite. If you need identity, do not pass the flag; if you want to check that a program is unchanged in every other respect, run it with `CUDA_VISIBLE_DEVICES=` set, which makes every device call decline and the output byte-identical.

## What it is worth

One `n x n` `linalg:matmul` on the interpreter, microseconds per call, warm. The machine is an NVIDIA GB10 (Grace Blackwell, 20 CPU cores), and the `--blas` column is the best this machine has: OpenBLAS across all twenty of them. Your device, driver and library will all differ, so measure.

| n x n | `--simd` f64 | `--blas` f64 | `--gpu` f64 | `--simd` f32 | `--blas` f32 | `--gpu` f32 |
|---|---|---|---|---|---|---|
| 64 | 46 | 21 | 139 | 27 | 11 | 42 |
| 128 | 359 | 42 | 53 | 195 | 26 | 36 |
| 256 | 2647 | 164 | 156 | 1453 | 85 | 71 |
| 512 | 20267 | 1160 | 735 | 10567 | 510 | 215 |
| 1024 | -- | 6450 | 5150 | -- | 3083 | 1183 |
| 2048 | -- | 89200 | 38000 | -- | 44600 | 8067 |

Read it in two directions. Against the lane kernel the device is 7x at n=128 and 28x at n=512, and 49x at n=512 in single float -- a different order of magnitude, which is the point of the flag. Against a tuned BLAS on twenty cores it is a wash until about n=256 and then 1.6x to 2.3x at double width, 2.4x to 5.5x at single: double-float is the width this class of device is worst at, so **`--gpu` pays most for `single-float` data**, which is what `torch:` builds by default.

The same products compiled to a `.class` and run on the JVM, best of three timed rounds after 400 warm-up calls:

| n x n | `--simd` f64 | `--blas` f64 | `--gpu` f64 | `--simd` f32 | `--blas` f32 | `--gpu` f32 |
|---|---|---|---|---|---|---|
| 64 | 50 | 17 | 107 | 32 | 8 | 106 |
| 128 | 345 | 30 | 50 | 206 | 34 | 34 |
| 256 | 2613 | 170 | 145 | 1380 | 95 | 65 |
| 512 | 20760 | 1140 | 740 | 10480 | 530 | 210 |
| 1024 | -- | 6933 | 5367 | -- | 4433 | 2233 |
| 2048 | -- | 91750 | 39000 | -- | 44625 | 8375 |

It is the same table, which is the point: once the product is one device call, the backend around it no longer matters. Warm carefully before you compare anything near the threshold -- at n=64 and n=128 the device drops back to its idle clock between calls, and a single cold round there can measure several times these figures.

And the stacked product, which is the shape a transformer is made of: one `linalg:matmul` of `batch` `n x n` slabs, microseconds per call, interpreter, same machine and same warm-up. `--blas` has no column here because it does not take this member.

| batch x n | `--simd` f64 | `--gpu` f64 | `--simd` f32 | `--gpu` f32 |
|---|---|---|---|---|
| 256 x 8 | 60 | 48 | 46 | 30 |
| 64 x 16 | 75 | 43 | 71 | 29 |
| 16 x 32 | 110 | 45 | 69 | 29 |
| 4 x 64 | 176 | 49 | 101 | 31 |
| 16 x 64 | 710 | 86 | 400 | 56 |
| 16 x 128 | 5580 | 300 | 3040 | 130 |
| 12 x 256 | 31740 | 1240 | 16660 | 380 |

The batch is what the device is for: the CPU pays for every matrix in the stack while the round trip is paid once, so the ratio grows with the batch as much as with the matrix -- 1.25x at the threshold, 26x at 12 x 256 double-float and 44x single.

The element-wise members, on the JVM class output: one `linalg:` call over 1.5 M elements -- the feed-forward activation of the transformer below -- microseconds per call, best of five timed rounds after 30 warm-up calls.

| 1.5 M elements | `--simd` f64 | `--gpu` f64 | `--simd` f32 | `--gpu` f32 |
|---|---|---|---|---|
| `exp` | 7300 | 833 | 7933 | 333 |
| `log` | 7267 | 800 | 7800 | 333 |
| `tanh` | 9533 | 767 | 9733 | 333 |
| `erf` | 103400 | 900 | 101233 | 333 |
| `sin` | 7667 | 733 | 9233 | 333 |

Nine to twelve times at double width, twenty-three to twenty-nine at single, and **115x for `erf`** -- the member the CPU is slowest at, and the one the exact `torch:gelu` is written over. The device column is flat because at this size it is the copy and not the arithmetic: every member costs what 12 MB up and 12 MB back costs, which is also why single float is worth twice double float here rather than the fraction the arithmetic would suggest. The refused members are not in the table because the flag does not change what runs for them; the numbers that refused them are in the section above. Measure each width in a process of its own if you repeat this -- on the CPU the second width measured through the same call site is 1.5x to 2x slower than the first, which is a JIT artifact and not a property of the width.

And the members whose CPU twin is an index odometer rather than a lane loop, on the JVM class output, at a transformer's own shapes: microseconds per call, best of three timed rounds after 50 warm-up calls.

| single-float, per call | `--simd` | `--gpu --simd` |
|---|---|---|
| `sub`, (4 256 256) against (4 256 1) | 442 | 88 |
| `sub`, (4 256 384) against (4 256 1) | 660 | 118 |
| `mul`, (4 256 384) against (384) | 665 | 115 |
| `sum :axis 2`, (4 256 256) | 202 | 75 |
| `sum :axis 0`, (4 256 384) | 297 | 70 |
| `var :axis 2`, (4 256 384) | 1387 | 475 |
| `transpose '(0 2 1)`, (4 256 192) | 335 | 75 |
| `softmax :axis -1`, (4 256 256) | 1915 | 402 |
| `sub`, (4 256 384) against (4 256 384) | 85 | 85 |

Three to six times. The last row is the same operation at an equal shape: the flag refuses it, so both columns are the CPU running the same lane loop -- offering it to the device instead measures 112 us, which is why it is refused. That contrast is the whole selection rule for this group.

End to end, `examples/llm-from-scratch/chapter03/train-gpt-soseki.lisp` at the notebook's own shapes (`*n-embd*` 384, `*block-size*` 256, which the file says is a one-line change) runs a training step **three to four times faster on the JVM class output** with the flag on -- 0.89 s against 0.21 s, from a 5-step and a 40-step run so that setup and sampling fall out of the slope, best of seven interleaved runs each. Quote the ratio rather than the digits: the same program varies by about 15% run to run on this machine.

**What is left of that step is no longer `linalg` at all**, which is worth knowing before you reach for another flag. An execution profile of the accelerated step spends about a third of it in the AdamW parameter update and another seventh in the dropout random numbers -- both per-element loops written in Lisp inside `torch:`, on no acceleration seam. The `linalg` kernels this page is about are around a tenth of it. On the **interpreter** the same program still shows no change at all -- 26.1 s per step against 25.5 -- and the reason is not the device: an interpreted step is 32 times a compiled one at the same shapes, so what dominates it is the tree walk around the kernels rather than the kernels. Compile the program before you measure a flag.
