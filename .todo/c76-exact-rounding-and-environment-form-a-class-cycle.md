# c76. `ExactRounding` and `Environment` form a class cycle `PackageCycleTest` refuses

Difficulty: Low

`PackageCycleTest.classCyclesAreOnlyTheDesignedMutualRecursions` fails on develop since
`07574538c` (c74): `ExactRounding` throws `Environment.divisionByZero()` (line 84) while
`Environment` calls `ExactRounding.mode`/`floatToInteger`, a cycle in `eval` with no designed
hub (`[class cycle in am.ik.rontolisp.eval without exactly one designed hub: [Environment,
ExactRounding]]`, measured 2026-10-04 on `d5f229283`).

Plan: build the division-by-zero error where it does not need `Environment` (move the factory
beside `LispEvalException.ofClass`, or let `ExactRounding` build it the same way), so the edge
goes; registering a hub would bless a cycle no design asked for.
