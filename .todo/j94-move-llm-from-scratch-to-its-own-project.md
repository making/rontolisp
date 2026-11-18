# j94. Move `examples/llm-from-scratch` out to a project of its own

Difficulty: Low

User decision (2026-11-18): split it out. The audit (`.todo/artefacts/j91-license-audit/README.md`,
row #9) found it rewritten "almost line by line" from the book code of
elith-co-jp/book-llm-from-scratch, whose repository states no licence.

- Prepare the new project with the directory's history (`git subtree split` or
  `git filter-repo --subdirectory-filter`) in a local directory outside this repository, with a
  README saying what it needs (a rontolisp release) and where it came from. Creating and
  publishing the remote repository is the user's: ask before any push to a new remote.
- Remove `examples/llm-from-scratch/` here, and every reference: `examples/README.md`,
  `examples/examples.yaml`, `examples/llm/README.md`, `doc/en/guides/neural-networks.md`,
  `doc/en/guides/gpu-acceleration.md` and their `doc/ja` mirrors, the `.kb` files that cite
  it (grep), `.todo/artefacts/123-gpu-acceleration/`. A measurement in `.kb` that used it
  keeps its numbers and names the new project instead of the path.
