# `lack-request` compiles on no backend: two pre-existing ceilings

Difficulty: High for the JVM half (a branch-relaxation pass over an emitter
that has never needed one), Medium for the WASM half. Recommend starting with a
Fable-class model; the two halves are independent and can be split.

Split out of `.todo/231` (2026-08-04), which pinned the lack chain on the
INTERPRETER only for exactly these two reasons. Neither is caused by the lack
work: both are documented invariants that a program of this size is simply the
first to reach. Both fail LOUDLY at compile time -- nothing ships silently
wrong -- so this is a coverage item, not a correctness one.

Acceptance: `LackEcosystemE2eTest`'s lack leg (urlencoded + multipart + session)
runs on the JVM class and on both WASM backends with the same output the
interpreter produces, and the test's javadoc + `.kb/lack.md` lose the
"interpreter-only" clause.

## 1. JVM: a method past the signed 16-bit branch offset

```
while compiling defun FAST-HTTP.PARSER::PARSE-HEADER-FIELD-AND-VALUE:
branch offset 36257 at position 268 overflows the signed 16-bit branch encoding
```

fast-http's parser is a generated `tagcasev` state machine. `.kb/jvm-method-size-md`
(`jvm-method-size-limits.md`) records the invariant: `GOTO_W` exists in
`am.ik.jvm.Opcode`, no emitter uses it, and there is no relaxation pass. The fix
is that pass -- rewrite an out-of-range conditional branch as
"inverted-condition short branch over a `goto_w`", then re-run to a fixpoint
because widening moves every later offset. Note the SECOND ceiling behind it: a
method's code array is at most 65535 bytes, which `goto_w` cannot rescue; measure
the emitted body first to learn whether this defun clears it.

## 2. Both WASM backends: a compound `concatenate` result type

```
Cannot compile concatenate: the result type must be a literal quoted
'list, 'vector or 'string designator
```

http-body's `slurp-stream` spells
`(apply #'concatenate '(simple-array (unsigned-byte 8) (*)) ...)`.
`.kb/concatenate-result-families.md` has the accepted family; the missing member
is the packed-octet-vector designator, which `.kb/packed-integer-vectors.md`
already gives `make-array` a lowering for. Check whether the JVM accepts it
(it may, via a different path) -- if so the fix is bringing the WASM side to the
shared normalizer rather than widening both.

## Why the tree-shaker cannot avoid either

`http-body:parse` dispatches to the multipart parser, so both defuns are
reachable from ANY `lack-request` program -- an exercise that only parses a
urlencoded body still drags them in. Verified 2026-08-04.
