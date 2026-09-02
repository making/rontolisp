# Un-displacing a remembered-element-type array loses the type on two backends

Difficulty: Medium

Found 2026-09-02 while closing `.todo/657` (`adjust-array` on a displaced array
un-displaces it, matching SBCL). `.todo/647` added the un-displace machinery
this reuses -- `LispArray.undisplace()` / `LispString.undisplace()`, JVM
`_arrayUndisplace`, wasm `_arr_undisplace` -- for `vector-push-extend`'s
growth of a full displaced view. It has a narrow but real cross-backend gap,
present since `.todo/647` landed and not previously measured: when the
array being un-displaced is a GENERAL array that REMEMBERS a non-packable
element type (`.todo/619`'s "the adjusted copy remembers the element type" --
reachable when a would-be-packed `:element-type` is combined with
`:fill-pointer`/`:adjustable`, or used above rank 1), the wasm backend
correctly resolves and carries that remembered type across the un-displace,
while the interpreter and the JVM backend both silently drop it back to `t`.

Repro (all four backends print the SAME source):

```lisp
(let* ((b (make-array 4 :element-type '(unsigned-byte 8) :adjustable t :initial-element 0))
       (v (make-array 2 :displaced-to b :displaced-index-offset 1 :adjustable t)))
  (adjust-array v 3)
  (print (array-element-type v)))
;; interpreter: T
;; JVM:         T
;; wasm:        (UNSIGNED-BYTE 8)
```

Measured 2026-09-02, on top of `.todo/657`'s change (which reaches this same
un-displace primitive from `adjust-array` too, not only from
`vector-push-extend`'s growth -- both call sites share the one gap).

Note SBCL is NOT a usable oracle for this specific repro: it rejects the
`make-array :displaced-to` call outright ("Can't displace an array of type T
into another of type (UNSIGNED-BYTE 8)") because CL requires a displaced
view's declared element type to be upgradable-compatible with the target's,
a check rontolisp's "lite" displacement semantics do not implement at all
(a separate, larger, already-out-of-scope gap -- `.kb/adjustable-arrays.md`,
"Displacement (`:displaced-to`)"). This item is about internal cross-backend
consistency (wasm vs. interpreter/JVM), not about matching SBCL's stricter
rejection.

## Mechanism (why wasm differs)

- **wasm**: the meta OFFSET word doubles as the element-type marker
  (`.kb/adjustable-arrays.md`, "A displaced view is not a BARE view").
  `_arr_undisplace`'s `emitRememberedMarker` walks the displacement chain to
  its end and copies THAT array's own marker word into the un-displaced
  view's now-freed offset slot -- 1 when the chain ends on a string, else the
  chain-end's own marker verbatim. This is exactly right for a general array
  whose target remembers `(unsigned-byte 8)`.
- **JVM**: `_arrayUndisplace` (`JvmArrayRuntimeBuilder`) rebuilds the header
  with LENGTH 3 (ordinary) or 4 (string/character-vector marker) ONLY -- it
  never considers length 5 (a header with a remembered element type in slot
  4). Whatever the chain would have resolved to, the un-displaced array
  always comes back as a plain length-3 header, `array-element-type`
  answering `t`.
- **interpreter**: a displaced `LispArray`'s constructor
  (`LispArray(int[] dimensions, LispArray target, int offset, int
  fillPointer, boolean adjustable)`) hard-codes
  `this.elementTypeCode = ArrayElementTypes.T` and `undisplace()` never
  touches `elementTypeCode` either -- there is no chain-walk anywhere in the
  interpreter's displacement code that asks the target what it remembers.

## Suggested plan (unmeasured -- confirm before committing to it)

Bring the interpreter and JVM up to wasm's (apparently correct) behavior,
rather than the reverse, since wasm's answer is the one a remembered-type
target's un-displaced view SHOULD carry (matching what
`%array-adopt-element-type` already does elsewhere for `adjust-array`'s own
fresh-copy stamp -- "copy the resolved word, do not re-derive the
representation").

- Interpreter: `LispArray.undisplace()` needs a chain walk (as `readFlat` /
  `writeFlat` already do) to find the terminal array/string and copy ITS
  `elementTypeCode` (or `ArrayElementTypes.CHARACTER` if the chain ends on a
  `LispString`) into `this.elementTypeCode` before clearing `displacedTo`.
- JVM: `_arrayUndisplace` needs a fifth possible new-header length (5) with
  slot 4 carrying the chain-resolved marker, mirroring wasm's
  `emitRememberedMarker` -- walk `header[3]`/`header[4]` hops the same way
  `emitResolveDisplacement` already does, ending on either a `String`
  (marker = character) or a header whose OWN slot 4 (if length >= 5) is the
  answer, else "remembers nothing".

Before touching either: confirm this is worth its blast radius against
`.todo/619`'s and `.todo/527`'s size/speed measurements for the array
runtime (a new header shape variant on JVM is not free), and write a failing
test first (interpreter `LispEvaluatorTest`, JVM `JvmLispCompilerTest`, wasm
already correct) pinning the SAME source across all three, rather than
assuming the plan above survives contact with the actual bytecode.
