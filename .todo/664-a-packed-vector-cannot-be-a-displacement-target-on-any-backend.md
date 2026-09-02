# A packed integer/float vector cannot be a displacement target on any backend

Difficulty: Medium

Found 2026-09-02 while closing `.todo/661` (a displaced view's element type is
its target's). Measured on all four backends, 2026-09-02:

```lisp
(let* ((b (make-array 4 :element-type '(unsigned-byte 8) :initial-element 7))
       (v (make-array 2 :element-type '(unsigned-byte 8) :displaced-to b
                        :displaced-index-offset 1)))
  (aref v 0))
;; interpreter: Unhandled condition: MAKE-ARRAY expects an array, got #(7 7 7 7)
;; JVM:         ClassCastException: class [J cannot be cast to java.util.ArrayList
;; wasm p1:     wasm trap: cast failure
;; wasm comp:   wasm trap: cast failure
;; SBCL 2.2.9:  7   (and (array-element-type v) => (UNSIGNED-BYTE 8))
```

The construction SBCL considers the CANONICAL one -- a view over a simple
specialized vector, the shape `:displaced-to`'s element-type rule is written
for -- is the one shape rontolisp refuses. Only a general array or a string can
be a displacement target here, which is why `.todo/661` could resolve a view's
element type through the chain for the price of a walk: every reachable target's
type is a remembered LABEL over boxed storage, never a packed representation.

The interpreter's message is at least a clear error; the two compile backends
raise a RAW host exception (`ClassCastException`) or a bare `cast failure`, with
no `fn: message` text, which is the same missing-diagnostic gap `.todo/627`
closed for `adjust-array` on a packed argument (`_ivRequireGeneral` /
`_fvRequireGeneral` on the JVM, no channel on wasm).

## Two possible shapes, decide on evidence

1. **Refuse clearly.** `make-array :displaced-to` over a `LispIntVector` /
   `LispFloatArray` (JVM `long[]` / `double[]`, wasm `TYPE_I8ARR`..`TYPE_F64ARR`)
   signals `make-array: cannot displace to a packed integer vector` on the
   interpreter and the JVM, and traps on wasm (the existing parity bar). Cheap,
   and it makes the three backends' diagnostics agree. Does NOT reach SBCL's
   behavior.
2. **Support it.** The view would have to resolve to PACKED storage at every
   element access, which is a second data-slot shape for the resolve walk on
   both compile backends -- the walk currently ends on "buckets array or
   string". Measure it before committing: `emitResolveDataAndIndex` is INLINE at
   every `aref`/`aset` site on wasm, so a third arm there is a per-site bill on
   the hottest array path, and `WasmLispCompilerTest
   .anElementAccessSiteDoesNotCarryItsOwnCopyOfTheSharedRuntime` caps it.
   `.kb/adjustable-arrays.md` ("A displaced view is not a BARE view", "A
   displaced view's element type is its TARGET's") has the layouts.

Shape 2 also has a knock-on: `.todo/661`'s "every displacement target's element
type is a remembered label" stops being true, and a view over a packed vector
would want the PACKED answer (`_ivElementType` / `_fvElementType`), which the
chain walk does not reach today.

Whichever lands, `.kb/adjustable-arrays.md` gets the measurement and the
`array-element-type` consequence, and the ci-spec case
`adjust-displaced-arrays-cross-backend` gets the leg.
