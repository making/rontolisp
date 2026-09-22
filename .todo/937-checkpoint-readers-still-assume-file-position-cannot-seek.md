# Checkpoint readers and their docs still assume `file-position` cannot seek

Difficulty: Low

Noticed while working `.todo/925` (2026-09-22).

## What is stale

`file-position` has been real on all four backends for a binary file stream since
`.todo/390` / `.todo/876` / `.todo/877`, and for a character one since `.todo/925`
(`.kb/read-load-streams.md`). These still say it answers nil / repositions nothing:

- `.kb/gguf.md`, "No seeking"
- `.kb/checkpoint-readers.md`, "Traps"
- `.kb/geom.md` (`geom::%skip-bytes`, "`file-position` answering nil by design")
- `.kb/quantized-matrix.md` (the `file-position` not seeking note)
- `doc/{en,ja}/reference/functions/checkpoint-skip-bytes.md` and
  `doc/{en,ja}/guides/running-a-checkpoint.md`

## What it needs

Decide per reader whether seeking past an unwanted tensor (instead of reading it through a
64 KB scratch buffer) is worth it -- measure the load time of a checkpoint that skips most of
its tensors -- and either switch `checkpoint:skip-bytes` / `geom::%skip-bytes` to
`file-position` or keep the walk and correct the text to say why.
