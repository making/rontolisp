# Metal's map threshold straddles a per-row libm call

Difficulty: Low

Filed 2026-09-02 out of todo-636, which found it and priced the consequence rather than
the fix. Read `.kb/gpu.md`, "The fused tier on Metal", the paragraph beginning "A FUSED
MEMBER CAN MOVE BITS THE CHAIN DID NOT".

`MIN_MAP_ELEMENTS` on Metal is `2^17`, and the comment above it says what it was measured
against: the element-wise map over a whole array, "the cheapest member taken (`sin`) is 45
against ~110 at 16384 and ~380 against ~180 at 131072". That is the right measurement for
the shape it was taken on. It is not obviously the right one for the shape a CHAIN
produces: a per-row intermediate. `linalg:log-softmax`'s
`(linalg:log (linalg:sum ... :keepdims t))` is a `log` over a `rows x 1` array -- 16384
elements at the book's shapes, an eighth of the threshold -- so it runs on the CPU while
every other member of that chain runs on the device.

## What it costs

Nothing incorrect, and the cost is not speed either at that size. What it costs is a
STRADDLE: the fused log-softmax pair runs that `log` on the device where the chain ran it
on the host, so the two differ in their last ulps and a training run's printed loss moves
on a Mac when the fused tier arrives. todo-636 measured that (six of its eight members
byte-identical, this pair not), decided the 104 ms of the ~330 ms the tier gives back was
not worth declining the pair over, and wrote the general rule down. This item is the other
end of it: remove the straddle instead of paying it.

## The question, which is a measurement

Is a device `log` over 16384 elements ahead of `Math.log` over 16384 elements HERE? The
threshold says a device `sin` over 16384 is 110 us against the CPU's 45 -- so probably
not, and then the answer is that the threshold is right and the straddle is the price of
it. But the numbers behind that line are `sin` at f64 on a whole array, and the operand
here is a freshly written f32 per-row array that the previous member left in the pool.
Measure it, at the shapes a chain actually produces (`rows x 1` for rows from 2^12 to
2^18), before changing anything.

Three outcomes, all of them results:

- the CPU wins at these sizes -- record the numbers next to `MIN_MAP_ELEMENTS` and close
  this, the straddle stays and `.kb` already explains it;
- the device wins from some lower row count -- then a per-shape rule (a map whose operand
  is a chain's own per-row intermediate) is worth having, and the fused tier and the chain
  agree again;
- it is a tie -- a tie is a decline, and this closes the way the axis fold's did.

## Not this item

Lowering the threshold for the WHOLE element-wise tier. The 2^17 measurement stands for
the shape it was taken on; what is in question is one shape it was not taken on.

## Acceptance

Either `MIN_MAP_ELEMENTS`'s comment gains the per-row numbers and this closes, or the map
offer rule gains the shape and `MetalGpuTest` gains a pin that the chain and the fused
log-softmax agree bit for bit at the book's shapes.
