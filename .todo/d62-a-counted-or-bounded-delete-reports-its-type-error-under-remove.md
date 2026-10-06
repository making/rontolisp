# d62. A counted or bounded `delete` family call reports its `type-error` under `remove`

Difficulty: Low

A bad sequence argument to a `delete` / `nsubstitute` / `substitute` spelling that carries a
bounding keyword is the operator's `SEQUENCE` `type-error`, but the compile paths name the
operator the expansion DELEGATED to, not the one the program spelled. Measured with
`(defvar n (read-from-string "5"))` and `handler-case` printing the condition:

| call | interpreter | JVM | P1 / component |
|---|---|---|---|
| `(delete 2 n)` | `DELETE: ...` | `DELETE: ...` | `DELETE: ...` |
| `(delete 2 n :count 1)` | `REMOVE: ...` | `REMOVE: ...` | no operator named |
| `(delete-if #'evenp n :count 1)` | `DELETE-IF: ...` | `REMOVE-IF: ...` | no operator named |

SBCL names none of them (its report is the argument's type), so the datum and class already
agree; only the text and the interpreter-vs-compiler split differ. Cause:
`expandDelete` / `expandDeleteIf` / `expandDeleteIfNot` (and the substitute-fresh-path of
`nsubstitute`) rewrite the head to `remove` / `remove-if` / `substitute` before calling the
shared lowering, which reads `headName(cons)` and `SeqScanBounds.operator` from it. The new
`:count` check reports under the same name (`.kb/sequence-bounding-keywords.md`, "A non-integer
`:count`").

Carry the spelled operator through the delegation (an operator argument on the shared
lowering, not a rewritten head), and pin the text on all four backends beside
`SequenceBoundsFixture.BOUND_REPORT_PROGRAM`. The P1 / component rows come from the wasm
operator table lacking a row for the rewritten head's `SEQUENCE` kind; check that after the
first change.
