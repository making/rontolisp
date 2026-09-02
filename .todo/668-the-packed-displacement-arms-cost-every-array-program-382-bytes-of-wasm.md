# The packed-displacement arms cost every array program 382 bytes of wasm

Difficulty: Low

Filed 2026-09-02 while closing `.todo/664` (a packed vector is a displacement
target). The measurement that item landed:

| where | wasm | class |
| --- | ---: | ---: |
| per array-using PROGRAM | +382 | +392 |
| per `array-element-type` site | +0 | +0 |
| per `make-array :displaced-to` site | +86 | +0 |
| first `:displaced-to` in a program | (in the per-site figure) | +84 |

The per-PROGRAM row is the packed read/write arms inside the shared
`_arr_get` / `_arr_set` bodies (`WasmArrayRuntimeBuilder`), which sit at FIXED
function indices and are emitted for every program, plus `_arr_undisplace`'s
packed marker walk. **A program that never displaces to anything, and a program
that cannot even build a packed vector, both pay it.** On the real programs that
is +0.15% (cl-ppcre), +0.21% (jzon), +0.65% (zlib, the smallest of the three) --
recorded in `.kb/adjustable-arrays.md`, "A PACKED vector is a displacement
target".

The JVM half already gets most of this for free: the class shaker drops
`_arrayMakeDisplaced` when unused, which is why the +84 row is only paid by a
program that displaces at all. Its +392 is `_rmGet`/`_rmSet`/`_arrayElementType`
/`_arrayUndisplace`, all intra-method and unshakeable, so the same gate would
help there too.

## The shape

Gate the packed arms on a program-level scan -- "this program can build a packed
integer vector or a packed float array" is enough on its own (a view can only
end on a packed target if one exists), and "and it has a `make-array` with
`:displaced-to`" is tighter still. The JVM already computes the first half:
`JvmLispCompiler.programUsesIntArray` / `usesFloatArray`, whose shape
(`makeArrayIsPackedInt`, alias-resolving through the deftype registry) is the
one to copy. The wasm backend has NO packed-usage flag -- it emits the packed
types unconditionally by design ("the farray types always exist on the GC
backend", `WasmArrayCompiler.compileElementType`) -- so this is a new scan
there, threaded into `buildArrGetBody` / `buildArrSetBody` /
`buildArrUndisplaceBody` the way `this.simd` already is, and into
`emitTargetDimsProduct` / `emitRememberedElementType` through `Ctx`.

**Measure before committing, and measure the RIGHT thing**: the win is 382 wasm
bytes on a program that never displaces, and 0 on one that does. Check first
whether the real programs in `size-report/programs` and `examples/asdf` actually
displace -- cl-ppcre does (`nsubseq` is a displaced string view) and their
deltas above are bigger than 382, so **a naive "does it displace" gate would buy
them nothing**; the packed-usage half is what would. The recipe (before/after
jars, `--optimize=size`, the q0..q4 control programs that separate the
per-program from the per-site term) is in `.kb/adjustable-arrays.md` under the
same section.

Do not take the gate on faith: if the measurement says the saving is smaller
than the scan's own complexity, **record the numbers in
`.kb/adjustable-arrays.md` and close the item** -- a change measurement says is
not worth its blast radius is a result.
