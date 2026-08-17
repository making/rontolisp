# 427. A syntactic multiple-value producer loses its extra values through a function return

Difficulty: High

```lisp
(defun f-gethash (h) (gethash "K" h))
(defun f-floor (a b) (floor a b))
(defun f-find (n) (find-symbol n))
(defun f-values (a b) (values a b))

(multiple-value-list (f-gethash h))   ; CL: ("V" T)        rontolisp: ("V")
(multiple-value-list (f-floor 7 2))   ; CL: (3 1)          rontolisp: (3)
(multiple-value-list (f-find "CAR"))  ; CL: (CAR :INHERITED) rontolisp: (CAR)
(multiple-value-list (f-values 1 2))  ; CL: (1 2)          rontolisp: (1 2)  -- correct
```

All four backends, identically. The last line is the tell: `values` PUBLISHES
its extras through the `%mv-spill` runtime channel, so they survive an
arbitrary call chain. `gethash`, the `floor`/`ceiling`/`round`/`truncate`
family, `find-symbol`, `intern` and `array-displacement` do not -- they are the
SYNTACTIC tier described in `.kb/multiple-values.md`
(`LispMacroExpander.isMvProducerForm` / `lowerMvProducer`): the consumer's own
expansion rebuilds the extra value from temps, so the second value exists only
when the producer is written LEXICALLY inside the consumer.

Any indirection at all erases it, silently and with no diagnostic:

```lisp
(multiple-value-bind (v f) (gethash "K" h)         (list v f))  ; ("V" T)   ok
(multiple-value-bind (v f) (progn (gethash "K" h)) (list v f))  ; ("V" NIL) wrong
```

A `progn`, a `let`, a `block`, a `defun`, a `defmethod` -- each one turns
`present-p` into an unconditional nil, i.e. "the key is absent". The failure
is a WRONG ANSWER, not an error, which is what makes this worth the difficulty
rating.

## Why it is now worth paying for

Found by the cl-mustache spike (`.todo/425`). Its context lookup is

```lisp
(defmethod context-get ((key string) (context hash-table))
  (gethash (string-upcase key) context))
```

-- the textbook shape: a method that IS the table read, existing so callers can
say `(multiple-value-bind (data find) (context-get key ctx) ...)`. Under
rontolisp `find` is always nil, so every `{{name}}` rendered as the empty
string and every `{{#section}}` was skipped. The library "loaded" and produced
wrong output on all four backends.

This is not a cl-mustache idiom. `gethash`-in-a-lookup-function is how every CL
library with a cache, an environment or a symbol table is written, and none of
them will report an error -- they will answer "missing".

## Where it goes

`.kb/multiple-values.md` already documents the two tiers and the reason the
syntactic one exists: no runtime multiple-value representation. The essential
fix is to retire the split -- have `lowerMvProducer`'s recognized producers
publish to `%mv-spill` the way `values` does, so the tier boundary stops being
observable -- rather than to add a case-by-case bypass. The syntactic lowering
should then survive only as the optimization it was meant to be: when the
producer IS lexically inside the consumer, keep emitting the temps and skip the
spill entirely, so the pinned byte-identity of those forms does not move.

Cost to watch, and the reason this is not `Low`: the spill is a global write,
so making `gethash` unconditionally spill would tax the hottest built-in in the
language on every call. The wiring probably has to be selective -- spill only
where the value can escape (a producer in tail position of a function body, a
`defmethod` body, a `block` result), which is a compiler-side analysis all
three compile paths and the evaluator must agree on.

## Definition of done

`(defun f (h) (gethash "K" h))` answers two values on all four backends, and so
do the `floor` family, `find-symbol`, `intern` and `array-displacement` through
the same indirection -- including through a `defmethod`, since that is the
shape that found this. `.kb/multiple-values.md` records what the two tiers now
mean and which forms stay byte-identical. Pinned in `LispEvaluatorTest` /
`JvmLispCompilerTest` / `WasmLispCompilerIntegrationTest` plus a `ci-spec.yaml`
case, with a size-report check that the syntactic fast path did not regress.
