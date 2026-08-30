# The remaining string producers still answer immutable values, so their results have no identity

Difficulty: High

Split out of `.todo/559` when its step 2 landed (2026-08-31). 559 made the
`subseq` / `copy-seq` string lane answer a MUTABLE CHARACTER VECTOR on the
compile backends, which closed the alias/callee/`replace`/`fill` divergences for
the strings a program allocates that way (`.kb/string-write-runtime.md`, "A
copy-seq/subseq result is mutable with identity" -- the mechanics, chokepoints,
sizes and corpus costs of that flip, all of which this item inherits).

Every OTHER producer still answers an immutable value (a bare `java.lang.String`
/ a `TYPE_STRING`), so the same divergence 559 fixed still exists one producer
over, on all three compile backends:

```lisp
(let* ((s (string-upcase "abc")) (a s)) (setf (char s 0) #\x) (list s a))
;; SBCL / interpreter: ("xBC" "xBC")     compiled: ("xBC" "ABC")
(let ((s (concatenate 'string "ab" "cd"))) (replace s "XY") s)
;; SBCL / interpreter: "XYcd"            compiled: "abcd" (the write is dropped)
(let ((s (format nil "~a" 42))) (fill s #\9) s)
;; same shape again
```

The producers, roughly in order of likely payoff:

- `concatenate 'string` (`compiler/ConcatenateForms.expand` -> `%string-concat`
  chain) and with it `string` / `princ-to-string` / `prin1-to-string`
- the case family `string-upcase` / `-downcase` / `-capitalize`
- `format nil` (the renderer's capture), `with-output-to-string` contents
- `read-line`, `getenv`, the fetch/socket read results, `symbol-name`?
  (CLHS says symbol-name's mutability is undefined -- probably keep immutable)
- `make-symbol`/`gensym` names, `map 'string`, `coerce 'string`, `reverse`,
  `remove`/`substitute` string results, `string-trim` family

## How to do it (the pattern 559 established)

Each flip is a per-site wrap, cheap to write: JVM `_strToCharVec` (clear the
fill-pointer slot like `_subseqCv` does) after the site builds its immutable
result, WASM `_str_to_cv` (`FUNC_STR_TO_CV`). Both helpers already exist. The
real work is NOT the wrap:

1. **Measure per producer.** `concatenate` is the string-building accumulator
   (`(setq acc (concatenate 'string acc piece))`): flipping it makes every later
   append re-render `acc` -- O(n) against an O(n) copy, a constant factor 2-3x,
   NOT quadratic, but it lands on jzon/format-heavy code. 559's corpus rows
   (json-stringify +36% JVM from flipping subseq alone) suggest the concatenate
   flip is where the real bill is. Measure before choosing per producer;
   landing a subset with the residue documented is fine (559 did exactly that).
2. **Boundary seams are mostly done.** 559 normalized the chokepoints (WASM
   `_str_to_mem`, `_write_line`, `_write_stream_str`, `emitStageStringParam`,
   `%str-byte-*`; JVM `JvmIoRuntimeBuilder`'s `_strv`-before-cast sites), so a
   charvec from a new producer flows through the same funnels. But each new
   producer multiplies charvec DENSITY, so re-run the string/json/http/socket
   test classes and the jose chain per flip.
3. **`format`/capture results come from the renderer**, whose output path is a
   single build site per backend -- one wrap, but `format` results feed
   `write-string`/http responses heavily; measure the render tax there.
4. A literal is still made by the reader and stays immutable; nothing here may
   change that (`.kb/string-write-runtime.md`).

## Definition of done

The three programs above (and the `f`-callee shape from 559's DoD, one per
flipped producer) answer identically on all four backends, matching SBCL, with
the `string-identity-cross-backend` ci-spec case extended per producer flipped;
the residue, if any, named in `.kb/string-write-runtime.md`'s residue paragraph
(which currently names exactly this item's list).
