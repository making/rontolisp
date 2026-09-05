# 703. Should an unknown `make-array` element type signal, rather than silently answer a boxed array?

Difficulty: Medium

Filed 2026-09-05 from `.todo/489` / `.todo/675`. The instance: on the JVM class output,
`make-array` with the element type in a VARIABLE answers a general array for `bfloat16`
while the literal at the call site answers the packed width, and the interpreter answers
the packed width both ways:

```lisp
(let ((e 'bfloat16))
  (print (array-element-type (make-array 4 :element-type e :initial-element 0.0))))
;; interpreter: BFLOAT16        JVM class: T
(print (array-element-type (make-array 4 :element-type 'bfloat16 :initial-element 0.0)))
;; BFLOAT16 on both
(let ((e 'single-float))
  (print (array-element-type (make-array 4 :element-type e :initial-element 0.0))))
;; SINGLE-FLOAT on both
```

(develop `21a82d97`). `checkpoint:make-tensor` passes the symbol as a variable, which is
how a `#bf16` checkpoint destination is inert on the JVM until the runtime dispatch learns
the third width -- that instance is fixed under `.todo/675`'s remainder, with a pin that is
literal AND variable over both widths on both backends (every existing case passed the
type as a literal; the variable path had no coverage, so the defect sat entirely on one
side of the condition -- `.todo/670` rule 6, the week's fourth instance).

**The mechanism question, which the instance fix does not answer.** `make-array
:element-type` with a type the backend does not recognise answers a boxed general array
and signals nothing (`.kb/checkpoint-readers.md` records it; `checkpoint:make-tensor`
exists to assert `array-element-type` afterwards because of it). Adding bf16 to the
dispatch fixes bf16 and leaves the mechanism ready for the fourth width, or for a typo:
`'single-flaot` produces a working array that is quietly general, and the cost surfaces
later as a performance cliff or a failed identity check with nothing pointing back at the
dispatch. The signature is the asymmetry itself -- a literal that works and a variable
that does not, on one backend and not the other.

## Do

1. **Ask what would break if it signalled.** CLHS lets `make-array` upgrade any element
   type to `t`, so a program passing `'fixnum` or `'(integer 0 255)` today gets a general
   array legally; enumerate the element types the code base, the shipped libraries and
   the doc examples pass (grep `:element-type`), and which of them are upgrades rather than
   packed widths. A warning under `--simd` for a type that names a packed width and did
   not get one is the narrowest version; an error for a symbol that names no type at all
   is the CLHS-conformant one.
2. Whatever is decided, the four backends must agree (`.kb/emitted-output-determinism.md`):
   a `ci-spec.yaml` case with the program above and each backend's answer.
3. Retire `checkpoint:make-tensor`'s assertion only if the dispatch signals itself; until
   then it stays.

Not to be worked inside `.todo/675` or `.todo/489`: the instance is fixed there, the
mechanism is this item.
