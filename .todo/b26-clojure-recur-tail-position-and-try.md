# Clojure: enforce `recur` tail position + the `try` boundary (shcloj4)

Difficulty: Medium (a tail-position walk over lowered bodies + a barrier flag on
the recur-target stack; no new runtime).

## Gap (verified 2026-10-01, oracle `clj` 1.12.6.1673)

```clojure
((fn [n] (try (if (zero? n) :t (recur (dec n))))) 3) ; oracle: Cannot recur across try
(do (recur 1)) ; oracle: Can only recur from tail position
```

rontolisp today (b17): the lowering is transparent through every non-`fn` form,
so both lower to a working self call instead of refusing.

## Design sketch

- Track a `try` barrier alongside the recur-target stack (`tryOf` sets it while
  its body and clauses lower): a `recur` with a `try` between it and its target
  is a named refusal, like the oracle.
- Tail-position analysis: only a `recur` in the tail of its target's body lowers;
  anywhere else is the oracle's `Can only recur from tail position` refusal. The
  tail walk covers the lowered shapes (`if` arms, `let*`/`progn` tails, `labels`
  bodies, `cond` arms, `do`, `when`, the dispatch lets) on shared code, so all
  four backends agree by construction.
- `lazy-seq` bodies are never tail position (deferred); that refusal falls out
  of the same walk, but the zero-arity target itself is b28.

## Acceptance

- `ClojureLoweringTest` pins both refusals with oracle transcripts; no
  `clojure-spec.yaml` output changes (all existing cases already recur in tail
  position outside `try`).
- `.kb/clojure-frontend.md` `recur` row updated.

## Depends on

b17 (recur targets). No dependency on b18-b25/b27/b28.
