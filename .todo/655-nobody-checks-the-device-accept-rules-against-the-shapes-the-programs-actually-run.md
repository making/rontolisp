# Nobody checks the device accept rules against the shapes the programs actually run

Difficulty: Medium

Two items in two days were the same defect: **an accept rule and the shapes actually
flowing through it were never compared.**

- `.todo/495` (Metal): `LinalgGpuTest` chose its fused-tier shapes from the FOLD
  threshold, and on Metal that threshold is `Long.MAX_VALUE`, so the shape collapsed to
  256 rows and the tier under test never ran -- while every assertion passed.
- `.todo/650` (CUDA, the production side): `torch:padding-mask` makes a `(batch 1 length)`
  mask and `suffixLength` accepts only a trailing suffix, so 96 of a step's 144 attention
  heads declined the fused member. Nobody had put the two facts side by side; the profile
  found it.

This item is the sweep neither of those did: for every `--gpu` member, **write down what
it accepts and what the programs and the tests actually hand it, and compare.**

## The scope

Every accept condition on a `linalg:` (and `vec:matvec`) device member -- not just
`suffixLength`: size thresholds, rank and axis rules, residency requirements, width and
boxing rules, broadcast compatibility. Both offer layers (`eval/LinalgGpu` and
`codegen/jvm/JvmGpuTemplate`, and see `.todo/654`: they are two copies).

Against two populations, because the defect shows up on both sides:

- **The programs.** `examples/llm-from-scratch` (the chapter-2 Transformer AND the
  chapter-3 GPT: their masks are different shapes, which is exactly why 650 hit one and
  not the other), `examples/llama2`, and whatever else `examples/examples.yaml` runs under
  `--gpu`. What shapes reach each member, and which of them are refused?
- **The tests.** A test whose shape is chosen from threshold A while exercising mechanism
  B passes without running the mechanism. Where does a test compute a shape from a
  constant it does not itself assert on?

## The output

For each member: the rule, the shapes the programs hand it, and either "accepted" or a
row saying which rule refused it and what the refusal costs. A refusal that costs nothing
is a RESULT -- `.todo/650` closed by measuring exactly that and leaving the rule alone.
Anything with a price gets its own item.

The cheapest instrumentation is the one 650 used: a counting hook on the offer functions
that tallies decline reason by member and shape over one training step, rather than
reading the rules and guessing which shapes arrive.

## Acceptance

The table exists in `.kb/gpu.md` (or a file it names), covering every device member, and
each refusal in it is either priced or filed.
