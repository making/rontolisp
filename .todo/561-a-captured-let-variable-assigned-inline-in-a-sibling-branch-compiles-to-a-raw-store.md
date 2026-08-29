# 561. A captured let variable assigned inline in a sibling branch compiles to a raw store

Difficulty: High (a wrong ANSWER, not a crash, in the dual-representation local
machinery -- the reproducer below happens to crash, but the same divergence
between a cell and a raw slot silently returns the wrong number when the types
line up)

The JVM backend miscompiles a `let` variable that is BOTH captured by a closure
in one branch arm and assigned INLINE in another, once the enclosing function is
big enough. The interpreter is right; the compiled program throws

```
class java.lang.Long cannot be cast to class [Ljava/lang/Object;
```

which is a boxed value sitting where the closure's `Object[1]` cell is expected.

## Reproducer (no library, no outlining)

`.todo/561-repro.lisp` shape -- generate with N = 90 rounds:

```lisp
(defun tree (x)
  (let ((acc 0) (hit nil))
    (block done
      (tagbody
         (if (= x 0)
             (let ((a0 (lambda ()
                         (setq acc (+ acc 1))          ; x N, with
                         (setq acc (logxor acc (car (list 1 0))))
                         (setq hit 'zero)
                         (go finish))))
               (funcall a0))
             (progn
               (setq acc (+ acc 2))                    ; x N, inline
               (setq acc (logxor acc (car (list 1 0))))
               (setq hit 'other)
               (go finish)))
       finish
         (return-from done (list acc hit))))))
(print (tree 0))
(print (tree 1))
```

- N = 80: interpreter and `-o Prog.class` agree.
- N = 90: the interpreter still agrees, the compiled class throws on `(tree 1)`
  -- the arm that assigns `acc` INLINE.

Nothing else moves across that threshold: both builds emit the same method SET
(same `_lambda_*`, one `_k$0`, and `_ubRead`), so it is not the body splitter
and not a new method. `_ubRead` is present in both, which puts the unboxed
dual-representation local (`.kb/jvm-int-fusion.md`) in the frame: the suspicion
is that the capture demands a cell while some assignment site still writes the
raw/boxed shadow, and which one wins turns on a size-dependent decision.

`rawBindingEligible` (`JvmIntFusionCompiler`) never asks whether the name is
CAPTURED; the comment says `JvmLetCompiler`'s `capturedInLet` covers it. Start
by checking that both agree for this shape -- and note `MAX_RAW_ASSIGN_SITES`
(64) and `MAX_LET_BODY_ASSIGN_SITES` (100) are both already exceeded at N = 80,
so the flip is NOT one of those caps.

## Why it matters now

`compiler/AstOutliner` (todo 560) creates exactly this shape deliberately -- it
moves a branch arm into a closure, which captures what the arm assigns while a
sibling arm keeps assigning inline. It is verified correct on the clack/ningle
compile it exists for, but it makes the latent bug reachable from source that
never mentions a closure.

## Acceptance

- The reproducer above agrees with the interpreter at every N, pinned as a
  `JvmLispCompilerTest` case.
- Whatever decides "this name needs a cell" is asked once, by one owner, rather
  than by two passes that can disagree; say which in `.kb/jvm-int-fusion.md`.
