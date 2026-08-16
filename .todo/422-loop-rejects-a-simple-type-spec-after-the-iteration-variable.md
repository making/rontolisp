# 422. `loop` rejects a simple type-spec after the iteration variable

Difficulty: Low

```lisp
(loop for v fixnum = 0 then (1+ v) ...)   ; => loop: incomplete for clause for V
```

ANSI's `for-as-clause` is `for var [type-spec] ...`, and `type-spec` has two
spellings: the explicit `of-type d-type-spec`, and the SIMPLE one -- a bare
`fixnum`, `float`, `t` or `nil` written straight after the variable.
`LispMacroExpander.LoopExpander.skipOfType` handles only the first, so the
second reaches `parseForPiece`'s subclause lookup, which sees `fixnum` (not a
loop keyword, so `peekKeyword` answers null) and reports the clause as
incomplete.

`with` has the same call and the same hole (`with x fixnum = 0`).

Found by the jose spike (`.todo/419`): ironclad's `math.lisp` writes
`for v fixnum = 0 then (1+ v)` twice, in the routine that strips the powers of
two out of a candidate -- so RSA key generation dies at macro-expansion time on
every backend. A three-line patch to `skipOfType` (match the plain, lowercased
member name against the four spellings, like `plainName` already does for
`being`-clause fillers) was the whole fix in the spike.

Like `of-type`, the declaration carries no semantics here -- rontolisp's loop
expansion is untyped -- so it is skipped, not recorded. The four names are the
whole of the simple spelling: anything else after the variable must stay an
error, or a typo'd subclause keyword becomes a silently ignored token.

## Definition of done

`for`/`as` and `with` accept `fixnum` / `float` / `t` / `nil` between the
variable and the rest of the clause, on all four backends (the expander is
shared, so one `LispMacroExpander` change covers them -- pin it in
`LispEvaluatorTest` plus a `ci-spec.yaml` case), a fifth name still errors, and
the `loop` doc page's clause grammar (en+ja) names both type-spec spellings.
