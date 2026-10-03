# c02. On the JVM a mutual tail call among local functions keeps a frame

Difficulty: High

Measured 2026-10-03 (exec jar with the JVM self-tail jump), compiled output run as
`java Prog` on the default 16 MiB worker:

- `(labels ((ev? (n) (if (= n 0) t (od? (- n 1)))) (od? (n) (if (= n 0) nil (ev? (- n 1)))))
  (ev? 1000000))`: `StackOverflowError`; Clojure's `letfn` twin overflows at 150,000 (passes
  100,000). The interpreter and both wasm backends answer `T` at 1,000,000.
- Two top-level `defun`s calling each other answer at 1,000,000, but only because C2 inlines
  one into the other and its frames are small: two direct `invokestatic`s a round, no proof.
- A self tail call is a jump since `.kb/jvm-self-tail-calls.md`; a tail call through a value
  in a `defun` bounces (`JvmTailBounce`); a `labels` lambda never bounces, and a call from
  one sibling to another is neither.

## Plan

- Measure first: how often CL and Clojure programs (the examples, the clojure-spec and
  ci-spec corpora, the spliced libraries) tail-call a sibling local function.
- Candidates: a `labels` group whose members tail-call each other compiled as ONE method
  with a member index and a dispatch at its head (the Scheme front end's tail-call groups,
  `.kb/scheme-frontend.md`, at the backend instead of the lowering); or the bounce extended
  to lambda bodies. Weigh each against the trampoline's measured per-call cost and
  `.kb/hot-path-method-size.md`.

## Pin

- `clojure-spec.yaml` (all four backends): a `letfn` even/odd pair 1,000,000 deep.
- A JVM compiler test: the `labels` pair above, and two `defun`s under `-Xint` (no inlining
  to hide the frames).
