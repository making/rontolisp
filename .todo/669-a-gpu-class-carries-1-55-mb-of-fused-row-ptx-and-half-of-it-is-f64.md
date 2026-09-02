# A `--gpu` class carries 1.55 MB of fused-row PTX, and half of it is `f64`

Difficulty: Low

Measured 2026-09-03 while pricing .todo/656 (`.kb/gpu.md`, "The JVM backend: the whole
library travels in the class", which now carries the table): `--gpu` adds **2,398,793
bytes** to a compiled class, the PTX is 78.6% of that, and the fused row family --
`softmax`, `softmax_grad`, `log_softmax`, `layer_norm*`, `gelu*`, 20 of the module's 58
entries -- is **1,548,866 bytes, 64.6% of everything `--gpu` adds**. The four softmax
entries alone are 663 KB.

Roughly half of the family is the `f64` twin of the other half (`softmax_f64` 174,127
against `softmax_f32` 170,811, and so on down). Metal does not support double at all
(`GpuDevice.supportsDouble`), so on that backend every one of those entries is dead weight
that still travels -- and the PTX travels to a Mac by design, since the machine that
compiles is not the machine that runs.

**Price the ceiling before touching anything** (`.kb/measurement-probes.md`, rule 2): the
most that can be saved is the `f64` half of the family, about 775 KB of a 2.4 MB class.
Whether that is worth anything at all depends on what a compiled class's size is worth,
which nothing in this repository currently measures -- `size-report/` tracks the WASM
outputs. If the answer is "nothing", the deliverable is that sentence in `.kb/gpu.md` and
no code.

**The cheap explanation is already ruled out**: the checked-in `gemm.ptx` carries no
`.loc` directives and no debug section at all, so the bulk is instructions, not metadata.
It is what a fully unrolled row reduction over two widths costs.

So if it IS worth something the only honest option is awkward and wants its own ceiling:
split the module and embed only the half a program's widths need. That is not a safe
static decision -- a program can reach `double` through `linalg` at run time whatever its
source says -- so it would have to be a flag with a documented failure mode, which is a
bigger change than the bytes are obviously worth. Establish the "worth something" half
first.
