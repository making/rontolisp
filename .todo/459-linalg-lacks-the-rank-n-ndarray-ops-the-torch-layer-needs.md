# 459. linalg lacks the rank-N ndarray ops the torch layer needs

Difficulty: Medium

Child of `.todo/458`. Everything here is **numpy parity** -- each entry is an
operation numpy itself exports and `linalg` does not -- so it stands on its own
merits, independently of the torch layer that needs it.

## The gaps

| Add | numpy / torch spelling | Needed by |
| --- | --- | --- |
| `(linalg:matmul a b)` for rank >= 3 | `np.matmul` stacked-matrix broadcasting = `torch.bmm` / `torch.matmul` | `attention.py`'s `torch.bmm(query, key.transpose(1,2))` -- every attention score |
| `(linalg:concatenate arrays &key axis)` | `np.concatenate` / `torch.cat` | multi-head concat (`torch.cat(head_out, dim=-1)`), greedy decoding's token append |
| `(linalg:stack arrays &key axis)` | `np.stack` | batching a list of per-sample arrays |
| `(linalg:expand-dims a axis)` / `(linalg:squeeze a &key axis)` | `np.expand_dims` / `np.squeeze` = `unsqueeze`/`squeeze` | `pos.unsqueeze(1)`, `mask.unsqueeze(1)`, the batch axis in `sinusoidal_position_encoding` |
| `(linalg:triu a &key k)` / `(linalg:tril ...)` | `np.triu` / `np.tril` | the subsequent (causal) mask, `create_subsequent_mask` |
| `(linalg:var a &key axis keepdims ddof)` / `(linalg:std ...)` | `np.var` / `np.std`; `ddof` 0 = torch's `unbiased=False` | LayerNorm (`utils.py`) |
| `(linalg:where cond x y)` | `np.where` | `masked_fill` (= `where(mask, -inf, score)`), and every "boolean-index" idiom that today has to multiply by a 0.0/1.0 mask |
| `(linalg:slice a specs)` | basic slicing `x[:, :n]` | `self.pe[:, :sequence_length]`, `pred[0, -1]`, taking a batch out of a corpus tensor |
| `(linalg:power a b)` | `np.power` / `**` | `10000 ** (dim_even / d_model)` in the positional encoding |
| `(linalg:softmax a &key axis)` / `(linalg:log-softmax ...)` | `scipy.special.softmax` / `torch.softmax` | attention weights, cross-entropy |

`softmax` is not in numpy proper, but `linalg` already carries `relu` for the same
reason (it is the array-level primitive an activation layer needs and it is
`--simd`-interceptable there); keep them together and say so in `.kb/linalg.md`.

## Design notes

- **Batched matmul**: numpy's rule -- the last two axes are the matrix, all leading
  axes broadcast. Implement it once as a `%la-matmul-nd` over the row-major flat
  index (the shape of `%la-fold-axis`: outer x M x K x N), and let the existing
  rank-2 `%la-matmul` stay as the fast path. Preserve the input width
  (`%la-etype`) like every other transform. This is the kernel a transformer
  forward pass spends its time in -- once it is correct, measure it under `--simd`
  and decide whether it wants an interceptor (`.kb/linalg-simd.md`).
- **Slicing needs a shape.** rontolisp has no `x[:, :n]` syntax, so pick a spelling
  and write it into `.kb/linalg.md`: proposal is one spec per axis, each `nil`
  (whole axis) or `(start end)` / `(start end step)`, with negative indices
  counting from the end and a missing trailing spec meaning "whole axis" --
  `(linalg:slice pe (list nil (list 0 seq-len)))`. Axes stay (numpy's `x[:, 0:3]`);
  dropping an axis is what `linalg:row` already does.
- **Options are `&key`, spelled literally at call sites** -- the 2026-08-19
  redesign, `.kb/linalg.md`. `:axis`, `:keepdims`, `:ddof`, `:k`, `:element-type`.
- **`-inf`**: `(/ -1.0 0.0)` evaluates to `-Infinity` on the interpreter today, but
  the masked-softmax path only needs `exp` of it to be 0.0. Pin what all four
  backends do with an infinity that flows through `where` -> `amax` -> `exp` ->
  `/` before committing `masked-fill` (in 460) to `-inf` rather than a large finite
  negative; note the answer in `.kb/linalg.md`.
- Every new function is rank-generic where numpy's is, and errors with the same
  `linalg: ...` message style as the existing ones.

## Acceptance

- `LispEvaluatorTest` + `JvmLispCompilerTest` + `WasmLispCompilerIntegrationTest`
  cover each new function, and the ones worth pinning end to end get a
  `ci-spec.yaml` case -- **all four backends**, per the governing rule in
  `CLAUDE.md`.
- Docs: a per-function page under `doc/en|ja/reference/functions/`, a
  `_catalog.yaml` entry and a `reference/functions.md` row for each, the
  `.kb/linalg.md` table extended (including the "rank <= 2 only" sentence, which
  `matmul` leaves once batched matmul lands), and the linear-algebra guide updated.
  Then `-Drontolisp.doc.fix=true` + `DocExamplesTest`.
- `PackageRegistry.LINALG_FUNCTIONS` + `LispNames` entries for every new name
  (otherwise they are misclassified as user symbols).

## Non-goals

Advanced indexing beyond `take-rows`/`gather`/`slice`, views (every linalg result
is a fresh array and stays that way), `einsum`, sparse arrays.
