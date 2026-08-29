# 562. A method past 255 local slots emits a truncated index

Difficulty: Medium (the fix is mechanical -- the `wide` prefix, or reusing dead
temporaries -- but every consumer of the bytecode has to decode the wider form:
`StackMapAugmenter`, `JvmClassShaker`, `osrHostileBackedges`)

`ALOAD`/`ASTORE` are emitted with a ONE-BYTE local index and nothing emits the
`wide` prefix, so a method needing more than 256 local slots silently writes
`astore 0` for slot 256. In a lambda, slot 0 holds the closure environment, so
the env array is overwritten by an unrelated temporary.

It surfaces as an unrelated-looking compile error, because the frame walk is
what notices:

```
StackMapAugmenter: method _lambda_212([Ljava/lang/Object;)Ljava/lang/Object;:
  aaload at 8827 on non-array type java/lang/Object (a reference merge was too lossy)
```

decoding to `... invokestatic; astore 0; aload 0; iconst_1; aaload` -- slot 0
holding an `Object` because slot 256 wrapped to it. Nothing guarantees the walk
notices: a truncated index that lands on a slot of the same type is a silent
wrong answer.

## Reproducer

A local function whose body is a long run of statements, each allocating a
temporary that is never reused:

```lisp
(defun tree (x)
  (let ((acc 0) (hit nil))
    (block done
      (tagbody
         (flet ((a0 ()
                  (setq acc (+ acc 1))                        ; x 500, with
                  (setq acc (logxor acc (car (list 1 0))))
                  (go finish)))
           (a0))
       finish
         (return-from done (list acc hit))))))
```

`flet` specifically, because `flet` gives its local the block CL mandates and a
body wrapped in a `block` is ONE item -- so `JvmBodyOutliner` finds no tail
spine to cut and the whole run lands in one frame. Spelled as the `let`-bound
lambda the same `flet` expands into, the splitter cuts it into `_k$N`
continuations and each gets its own frame, which is why it compiles. That is a
workaround, not the fix: it is luck that the shape that overflows is also the
shape the splitter declines.

Two independent things to settle:

1. **The index.** Emit `wide` past 255, and teach the three bytecode readers to
   decode it (`.kb/stackmap-augmenter.md` already lists variable-length
   instruction decoding as unstarted, for `tableswitch`). Or refuse the compile
   loudly instead of truncating -- strictly better than today even alone.
2. **The count.** `ctx.nextLocal` only ever grows, so a straight-line body burns
   a slot per temporary and reaches 256 in a few hundred statements. Freeing a
   temporary's slot at the end of the form that minted it would keep ordinary
   programs far away from the limit.

## Acceptance

- The reproducer compiles and runs, or fails with a message naming the limit.
- A `JvmLispCompilerTest` case over a body past 256 slots.
