# `:test` / `:key` keyword args for sequence & alist functions (missing)

**Status:** partially implemented (`member` already accepts `:test`). The rest
ignore or reject these keywords, which forces non-idiomatic code (e.g. you cannot
look a string up in an alist with `assoc ... :test #'string=`; `assoc` errors with
"assoc expects 2 arguments, got 4").

## What to add

- **`:test`** on `assoc`, `rassoc`, `find`, `position`, `count`, `remove`,
  `delete`, `remove-duplicates`, `union`, `intersection`, `set-difference`,
  `adjoin`, `substitute`/`nsubstitute`. Default stays `eql`; common explicit
  values are `#'equal` / `#'string=` / `#'=`.
- **`:key`** on the same set (apply a selector to each element before testing),
  which CL code leans on heavily.

`member` is the existing reference implementation for `:test` parsing -- follow
its shape for the others.

## Where to implement

- `Environment.java` -- the interpreter builtins (these live in the
  `registerSequenceOps` / assoc registration area; `member`'s `:test` handling is
  the template). Update `requireArgCount` call sites so the keyword pairs are
  parsed instead of rejected.
- JVM/WASM: the matching `Jvm<Name>Compiler` / `Wasm<Name>Compiler` for each
  function; keyword args must be resolved (statically where possible, like the
  existing `member` handling).
- README "Built-in Functions" (note which keywords each supports) +
  `ReadmeExamplesTest`; add E2E cases to `ci-spec.yaml`.

## Why it matters

Unblocks string-keyed alists (a lighter alternative to hash tables, which have
since landed -- see [03-text-analysis-example-blocked](03-text-analysis-example-blocked.md))
and a large class of ordinary CL list code.
