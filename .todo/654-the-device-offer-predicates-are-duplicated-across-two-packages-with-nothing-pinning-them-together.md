# The device-offer predicates are duplicated across two packages with nothing pinning them together

Difficulty: Medium

Which shapes `--gpu` ACCEPTS is decided twice, in two files that share no code and no test:

- `src/main/java/am/ik/rontolisp/eval/LinalgGpu.java` -- the interpreter's interceptor.
- `src/main/java/am/ik/rontolisp/codegen/jvm/JvmGpuTemplate.java` -- the copy the compiled
  program carries.

Both sit ABOVE `am.ik.gpu`, so neither backend can correct a disagreement between them: a
shape one accepts and the other declines is a program that runs `java -jar` and
`-o out.class` down different paths, at the same inputs, with nothing failing.

## What is duplicated

Twelve helpers share a name across the two files:

```
batchStride  bcast  bcastShape  bcastStrides  copyInto  foldAxis
map  resident  rowMajorStrides  sameShape  scale  zip
```

and one pair does NOT, which is the worst case of all: `LinalgGpu.suffixLength` and
`JvmGpuTemplate.softmaxMaskLength` are the same predicate written twice under two names,
so `grep -rn suffixLength` finds one of them and reads as if it were the only one. That
pair was found by hand while closing `.todo/650`; nothing found it before.

`grep -rlw` over `src/test/java/` returns ZERO files for eleven of the twelve names, and
eight for `bcast` and `resident` -- which are almost certainly hits on `linalg::%la-bcast-*`
and on the English word, not on either Java helper. **Nothing pins the two implementations
to each other.** CLAUDE.md requires that where behavior must be identical across the
interpreter and the backends, the topic's `.kb` file says so and NAMES the pinning test.
This group is named nowhere.

## The work: ONE differential test, not thirteen

A per-helper pin fixes only that today's thirteen agree. It says nothing when a
FOURTEENTH is added to one side -- which is exactly how `suffixLength` /
`softmaxMaskLength` got past everyone, since a helper-name net never had them in it.

Write instead a single differential test: run one set of shapes through BOTH paths -- the
interpreter's `LinalgGpu` and a `-o out.class` compile -- and assert

- (a) the two AGREE on accept vs decline, and
- (b) where they accept, the results are bit-identical.

Choose the shapes at the ACCEPT BOUNDARY rather than for coverage: a mask that is a
trailing suffix and one whose middle axis is extent 1 (the `(batch 1 key)` shape
`.todo/650` was filed for), an exactly-equal pair, a rank mismatch, a fold on the last
axis and a fold that is not, a resident operand and a fresh one, both widths.
`LinalgGpuTest` already runs about eight minutes, so keep the set to the boundary.
`JvmLinalgGpuAccelCompilerTest` already drives both paths and is the place to look before
starting a new test class.

Then, separately, read the thirteen bodies against each other and record which are
word-for-word identical, which say the same thing differently, and which are not the same
predicate at all. The dangerous half is not uniform: `sameShape` / `bcastShape` /
`foldAxis` / `suffixLength` decide ACCEPTANCE (a disagreement is a performance fork), while
`bcastStrides` / `batchStride` / `rowMajorStrides` are ARITHMETIC (a disagreement is a
wrong answer). **A body that actually differs is its own item** -- file it separately
rather than folding the fix into this one.

## Acceptance

The differential test exists, is named in `.kb/gpu.md` beside the accept rules it pins,
and fails if either path's predicate is changed alone. The thirteen-pair comparison is
recorded; anything found to differ is filed.
