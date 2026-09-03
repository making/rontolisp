# 682. The engine outgrows the name: `examples/llama2` -> `examples/llm`

Difficulty: Low

Deferred decision from `.todo/676`, which asked whether `llama2.lisp` should become
`llm.lisp` and decided **not yet**. The forward pass is now a table of layer kinds and
the file carries an architecture table with rows for `qwen3`, `smollm3` and `granite`,
so the name is already half false; what keeps it is that nothing but karpathy's `.bin`
actually loads yet, and three sibling items (`.todo/673`, `.todo/675`, `.todo/489`) name
the path `examples/llama2/llama2.lisp` in plans that are in flight. Renaming under them
buys nothing and costs a merge.

**The trigger: the first published checkpoint (a GGUF or a safetensors, not a `.bin`)
that runs end to end.** At that point the directory is no longer a port of llama2.c, and
the rename is one commit rather than two half-measures.

## Do

- `git mv examples/llama2 examples/llm`, `llama2.lisp` -> `llm.lisp`. The `.bin` loader,
  `stories260K.bin`, `tok512.bin` and `download-stories15M.sh` stay -- the smallest
  checkpoint that runs is worth keeping as the fast story test.
- The `LLAMA2_*` environment fallbacks are run.c's interface and part of what
  "ported whole" means; keep them, and add `LLM_*` aliases only if a caller wants them.
- Update in the same commit: `examples/examples.yaml` (two entries, `path` + `workDir`),
  `examples/README.md`, the directory's own `README.md`, `doc/en/guides/{simd,blas,gpu}-acceleration.md`
  AND their `doc/ja` mirrors (byte-identical code fences), `doc/{en,ja}/reference/functions/read-sequence.md`,
  `.kb/binary-sequence-io.md`, `.kb/linalg-blas.md`, `.kb/simd-parallel.md`, `.kb/jvm-typed-loops.md`.
  `grep -rn 'examples/llama2\|llama2\.lisp'` over the tree is the checklist.
- `./mvnw -Dtest=ExamplesE2eTest -DfailIfNoTests=false -Drontolisp.examples=true -Drontolisp.examples.only=llm test`
  is the verification; the stories must be unchanged.

## Not this item

Splitting the file. It stays ONE file with the loaders, the tokenizers, the layer table
and the sampler in it -- that is the point of the example.
