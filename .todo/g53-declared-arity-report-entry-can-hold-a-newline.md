# g53: A declared arity-report entry can embed a newline and shift every name after it

Difficulty: Small

Parent: `.todo/g46-jvm-leg-arity-messages-lost-in-full-corpus-runs-since-g42.md` (the
same `_arityMsg` names list; found while implementing g49's chunking).

## What diverges

`JvmArityOperators.shape` stores a declared report as `DECLARED_MARK + (char) prefixIndex
+ suffix` (`JvmArityOperators.java`). The registry index is dense 1-based, so the 10th
distinct string to register makes some entry's second char a newline (`(char) 10`).
`_arityMsg` / `_arityOp` join the names on `"\n"` and `split` them back
(`JvmRuntimeBuilder.buildArityMsgBody`, `buildArityOpBody`), so that one entry splits
in two and every operator after it resolves to the wrong name -- silent wrong text
(`Wrong number of args (0) passed to: my.app/OTHER` naming a neighbour), the same
family as g46/g49. `countChunkEntries` overcounts the same way.

Whether index 10 lands on a *prefix* (the only index stored in an entry) depends on
registration order: each declared `(prefix, entry)` pair takes two consecutive indices,
so with no builtin names interleaved the prefix indices stay odd and `'\n'` never
occurs (which is why the g49 e2e with 1,200 distinct prefixes passes); an odd number
of built-in operator names registering first flips the parity and a prefix lands on
10.

`encodable` gates the prefix/suffix *strings* against newlines but not the index char.
Reproduced standalone 2026-10-22: 12 distinct prefixes through the real registry
shape, `String.join` + `split` recovers 26 pieces from 25 entries with every name
from the split point shifted by one (see g49 session notes).

## Acceptance

- Either the join/split stops being newline-delimited (length-prefix, a separator no
  entry can hold, or a second parallel table), or `shape` refuses a report whose entry
  would hold one (a compile error naming the limit), with a synthetic case of 10+
  distinct declared prefixes asserting every one still reports in its words on the
  JVM leg (and the same shape on the other three backends, which share the
  declaration but not the join).
- Keep the g49 chunking byte-identical for registries without one: single-chunk
  programs must not change bytes.
