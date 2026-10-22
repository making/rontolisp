# g51: The wasm arity operator-names ceiling silently reports Function past 65,535 funcIds

Difficulty: Easy

Parent: `.todo/g48-audit-the-wasm-arity-report-path-for-a-span-cliff.md` (the audit
found no live cliff; this is the one ceiling it named, the wasm half of g49's
JVM-side budget).

## What diverges

A callee shape carries the reported operator's funcId in the sixteen bits above
`WasmRuntimeBuilder.ARITY_FUNC_ID_SHIFT`, which end at `ARITY_MAX_NAMED_FUNC_ID`
(65,535). At and past it four independent gates drop the name and the callee reports
as `Function`:

- `arityShape` carries no funcId (`funcId + 1` would wrap onto a smaller id);
- `arityOperatorFuncIds` / `declaredArityFuncIds` never register the id;
- an unpaged per-arity dispatcher's named-arms gate
  (`missShapes.lastKey() < ARITY_MAX_NAMED_FUNC_ID`) drops the names for its whole
  span, so one over-limit miss renames every miss of that dispatcher;
- the eval runtime's unbacked `EVAL` id is not issued past the ceiling.

The over-limit behavior is silent generic text (`Function expects 1 argument, got 2`
instead of `LENGTH expects ...`), never a lost signal or a trap -- the same family as
g49's JVM names budget. The clojure-spec corpus sits near 30,500 dispatchable funcIds,
so the ceiling holds with ~2x headroom today.

## Acceptance

- Either the names ride past the ceiling (a second shape word, chunked like g46),
  or the drop turns loud (a compile refusal naming the budget), or the mechanism
  stays documented-as-intended with its pin.
- Pinned today by `WasmAritySpanTest` (shape drop, both census caps, the unpaged
  whole-dispatcher name drop at 70,000 funcIds) -- the follow-up keeps those green
  and adds the behavior it chooses.
