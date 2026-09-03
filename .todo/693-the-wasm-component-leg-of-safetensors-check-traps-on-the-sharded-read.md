# 693. The `wasm-component` output of `safetensors-check.lisp` traps on the sharded index read

Difficulty: Medium

Found 2026-09-03 while closing `.todo/692`, and **not** what 692 was about: it is
present with and without `--simd`, and present on the jar built from the commit
BEFORE 692's fix (checked directly against a worktree at that commit). 692's own
reproducer passes on this backend; this one does not.

```
cd examples/llama2
rontolisp safetensors-check.lisp -o prog.wasm --component
wasmtime run --dir . prog.wasm
```

The program prints correctly through the single-file sections and dies at the
`-- sharded` section (`safetensors:read "safetensors-check.index.json"`, line 67):

```
wasm trap: cannot read after being notified that the writable end dropped
```

Twenty-eight of the expected thirty-five lines are already out, so the failure is
squarely the index path, not start-up. The Preview 1 (`-o prog.wasm`) output runs
the same file to completion, and so do the interpreter and the JVM.

## Why nobody saw it

`examples.yaml`'s `wasm-component` token is a **COMPILE-only** leg in
`ExamplesE2eTest` (the RUN tokens are `interpreter` / `jvm` / `wasm`; see that
class's header comment). So the entry has been green since the day the WASM legs
were added, on a program that has never once been RUN on that backend. `.todo/675`
recorded "the plain `wasm` / `wasm-component` legs pass the whole fixture", which
is true of `wasm` and is a compile-only claim for `wasm-component`.

**The general shape is worth more than this instance**: any example whose
`backends` list has `wasm-component` and whose subject is I/O has an unrun leg.
Grep for the token before assuming an example is verified on that path.

## What is already ruled out

Two probes, both under `--component`, both PASS, so neither is the trigger:

- three sequential `with-open-file` binary reads, one file opened twice;
- a text stream held open across a nested binary `with-open-file`, then read again
  after the inner one closes.

So it is narrower than "a second file" or "a nested open". The next places to look
are the component I/O adapter's handle lifetime against what `safetensors:read`
does for an index (open the JSON, parse it, then open each shard named by it --
possibly re-opening a shard, possibly holding the JSON's handle), and
`checkpoint:skip-bytes` on a component stream.

## Do

1. Reduce the reproducer to the smallest program that traps, using the two passing
   probes above as the starting point to bisect against.
2. Fix the component I/O adapter (or the reader, if it is the reader).
3. **Give the example a leg that would have caught it.** A compile-only token
   cannot; either the manifest grows a RUN token for the component output, or this
   fixture gets a `ci-spec.yaml` case (that driver runs the component and compares
   output). The second is cheaper and is where the other cross-backend output
   comparisons already live.
4. Re-check the sibling examples the grep above turns up.
