# A displaced view forgets its own `:element-type` on all four backends

Difficulty: Medium

Found 2026-09-02 while closing `.todo/658` (un-displacing must not change
`array-element-type`). That item's measurement exposed the level-up gap it was
standing on: **a `:displaced-to` view does not record its own `:element-type`
at all.** Measured on all four backends, 2026-09-02:

```lisp
(let* ((b (make-array 4 :element-type '(unsigned-byte 8) :adjustable t :initial-element 0))
       (v (make-array 2 :element-type '(unsigned-byte 8) :displaced-to b
                        :displaced-index-offset 1 :adjustable t)))
  (print (array-element-type v)))
;; interpreter / JVM / wasm preview 1 / wasm component: T   (SBCL: (UNSIGNED-BYTE 8))
```

The keyword is accepted and dropped. Consequences today:

- `array-element-type` of a view lies (`T` for a view that asked for something
  narrower), so `.todo/658`'s "un-displacing keeps the view's own element type"
  invariant is satisfied by keeping a value that was already wrong. Fixing this
  item makes the un-displace's answer right for the right reason -- the
  un-displaced array should then answer the VIEW's recorded type, and the wasm
  `_arr_undisplace` marker (currently "1 when the chain ends on characters,
  else 0") becomes "the view's own recorded marker".
- CL requires a view's element type to be type-equivalent to its target's
  (`make-array`, `:displaced-to`), and SBCL 2.2.9 REFUSES a mismatch outright
  ("Can't displace an array of type T into another of type (UNSIGNED-BYTE 8)").
  rontolisp's lite displacement performs no such check, which is why the
  `.todo/658` repro is constructible here at all. The check is the natural
  second half of this item, but it is a behavior change that can reject
  programs that run today -- decide it on its own evidence.

## Where the type would have to live

- **Interpreter**: `LispArray`'s two displaced constructors hard-code
  `this.elementTypeCode = ArrayElementTypes.T` (`LispArray.java`, the
  `LispArray(int[], LispArray, int, int, boolean)` shape). The field already
  exists and `undisplace()` already leaves it alone, so this backend is the
  cheap one: thread the resolved code in from
  `Environment`'s `make-array` displaced arm.
- **JVM**: a displaced header is `Object[]{dims, fp, adj, target, offsetLong}`
  (7 for a string view) and slot 4 is the OFFSET -- the slot an ordinary header
  spends on the remembered type. There is no free slot; the header would have to
  grow (a length-6 displaced shape, or the type appended after the offset), and
  every "displaced?" test that reads the header LENGTH has to move with it
  (`emitResolveDisplacement`, `_arrayDispTarget`, `_arrayDispOffset`, `_strv`,
  `stringp`'s length-4 test, `_arrayUndisplace`'s 7->4 / 5->3 rebuild, and the
  raw shape re-test in `JvmIntFusionCompiler` -- `.kb/adjustable-arrays.md`,
  "A PLAIN general array starts PACKED").
- **wasm**: the meta OFFSET word IS the element-type marker word
  (`.kb/adjustable-arrays.md`, "A displaced view is not a BARE view"), so a
  displaced view has literally nowhere to put a second number without extending
  the meta chain by a cons -- which every array pays for, displaced or not,
  unless the chain is extended only for displaced arrays and every meta reader
  learns the two shapes.

## Before committing to it

Measure the size cost on both compile backends first (`size-report`, and a
one-`make-array` program), the way `.todo/619` and `.todo/527` did: a header
shape variant is not free on the JVM and a longer meta chain is not free on
wasm. `array-element-type` of a displaced view answering `T` is a narrow lie;
paying a per-array word everywhere to fix it may not be worth the blast radius,
in which case **the measurement is the deliverable** -- record it in
`.kb/adjustable-arrays.md` under the `.todo/658` section and close this item.
