# Milestone: a plain `(make-string n)` is mutable on the compile backends too

Found 2026-07-29 writing `examples/db/postmodern-crud.lisp`. Every s-sql query
whose SQL is assembled at RUN time -- i.e. any S-SQL form carrying a value that
is not a literal -- reaches the server as a BLANK string on the JVM, on the WASM
component and on Preview 1, while the interpreter sends the real statement.
Nothing is signalled: PostgreSQL answers a `WARNING: Empty query sent.` notice,
the insert or update simply does not happen, and the program keeps running.

```lisp
(ql:quickload "postmodern")
(format t "[~a]~%" (pomo:sql (:insert-rows-into 'fruits :columns 'id :values '((1) (2)))))
;; interpreter:                    [INSERT INTO fruits (id) VALUES (1), (2)]
;; JVM / component / Preview 1:    [                                       ]
;;                                  ^ spaces, and exactly the right length
```

Same for `(pomo:execute (:insert-into 'fruits :set 'id 3 'price (* 3 100)))`:
the row is silently not inserted on the three compile backends. It is not
s-sql-specific -- `strcat` below is the ordinary CL idiom "allocate a buffer,
write into it, return it", and it is the whole reason `.todo/202`'s milestone
program had to be written with literals only.

## The four holes, one root cause

```lisp
(let ((s (make-string 4))) (setf (char s 0) #\a)     (format t "[~a]~%" s))
(let ((s (make-string 4))) (setf (subseq s 1 3) "xy") (format t "[~a]~%" s))
(let ((s (make-string 4))) (replace s "ab")           (format t "[~a]~%" s))
(let* ((s (make-string 4)) (alias s)) (setf (char s 0) #\a) (format t "[~a]~%" alias))
```

| form | interpreter | JVM / component / Preview 1 |
| --- | --- | --- |
| `(setf (char s 0) #\a)` | `[a   ]` | `[a   ]` (through the setq rebuild) |
| `(setf (subseq s 1 3) "xy")` | `[ xy ]` | `[    ]` **lost** |
| `(replace s "ab")` | `[ab  ]` | `[    ]` **lost** |
| the write seen through an alias | `[a   ]` | `[    ]` **lost** |

`(make-string n)` is an IMMUTABLE string on the compile backends: a mutable
character vector is what `make-array :element-type 'character` WITH
`:fill-pointer`/`:adjustable` gives (`.kb/adjustable-arrays.md`). So the runtime
`(%arrayp seq)` test in `LispMacroExpander.expandReplace` is false and the
expansion takes its FUNCTIONAL branch: it builds the correct new string, returns
it, and the caller throws it away because the call is in statement position.
`(setf (subseq ...))` lowers onto that same `replace` (`LispMacroExpander` line
~3193), which is why it goes the same way. `(setf (char ...))` looks like it
works only because `expandScharSetFunctional` `setq`s the rebuild back into the
variable -- the same trick cannot reach an alias. The interpreter never expands
`replace` (`LispEvaluator` says so deliberately at line ~2572): its `LispString`
is mutable and `Environment`'s `replace` writes in place.

The library end of it:

```lisp
(defun s-sql::strcat (args)               ; verbatim upstream
  (let ((result (make-string (reduce #'+ args :initial-value 0 :key 'length))))
    (loop :for pos = 0 :then (+ pos (length arg))
          :for arg :in args
          :do (replace result arg :start1 pos))
    result))
```

## Goal

`(make-string n)` on the compile backends is a MUTABLE string -- lower it to the
character-vector representation that `.kb/adjustable-arrays.md` already
describes, so all four rows of the table above agree with the interpreter and
with Common Lisp. That is the essential fix; the `setq` widening below is the
patch it replaces, not an alternative to weigh on its own merits.

A second payoff: today's `expandMakeString` fills the string with a `dotimes`
that `concatenate`s ONE character per iteration, so allocating the buffer is
O(n^2) -- s-sql's 67-character statement costs 67 string copies before a single
byte is written into it. The array lowering removes the loop entirely.

## What to work through

- **The normalization surface is already built.** A character vector renders
  through `_strv` (JVM) / `_charvec_to_str` (WASM) at the string-op compile
  sites listed in `.kb/adjustable-arrays.md`. Walk that list against what a
  `make-string` result is actually passed to in real code (`format`, `princ`,
  `concatenate`, `string=`, `subseq`, hash-table keys, `intern`, stream writes)
  and pin the ones no test covers today.
- **The known residue becomes reachable.** `.kb/adjustable-arrays.md` records
  `eq`/`eql` of a char vector vs an equal-content string as content-true on the
  JVM and nil on WASM. Today no `make-string` result can hit that; after the
  change every one can. Decide and pin it, or the divergence just moves.
- **`Ctx.usesArrays` (JVM).** The array runtime is emitted only when the program
  uses an array op; lowering `make-string` onto it flips that gate for
  string-only programs. Byte-identity contracts (`.kb/emitted-output-determinism.md`)
  and module size both move -- decide whether `make-string` alone should pull in
  the array runtime, or whether the lowering is conditional on the program
  really mutating the string (a `FreeVarAnalyzer`-style scan, cheap to get
  wrong: `strcat` mutates through a call the scan does not see).
- **`--no-gc` is out of scope, confirmed**: `make-string` is already an
  unsupported operation there (`--no-gc: unsupported operation 'MAKE-STRING'`),
  and its `make-array` takes float element types only. Nothing to preserve.
- **Keep the functional branches.** A LITERAL string is still immutable
  (`(replace "abc" ...)`), so `expandReplace` / `expandScharSetFunctional` keep
  their `%arrayp`-false paths; what changes is which values reach them.
- **The interpreter does not move.** This is convergence onto its behavior, so
  every new pinning test must assert the same text on all four backends.

If the representation change turns out to be the wrong call, the minimum that
closes s-sql is: in `expandReplace`'s functional branch, when the first argument
is a SYMBOL, `setq` the rebuild back into it -- the precedent
`expandScharSetFunctional` sets in the same file. It leaves the alias row of the
table broken and needs the same treatment in the `(setf (subseq ...))`
expansion, so record it as a stopgap with its own re-evaluation trigger rather
than as the answer.

## Acceptance

- The four-row table above prints identically on the interpreter, the JVM and
  both WASM backends, pinned by a ci-spec case (a name in the shape of the
  existing `mutable-strings-cross-backend`).
- `(pomo:sql (:insert-rows-into ...))` and an S-SQL form with a computed value
  produce identical SQL on the interpreter, the JVM and the component -- a leg
  in `PostmodernE2eTest`, whose milestone program uses literals only, which is
  exactly why `.todo/202` did not catch this.
- `examples/db/postmodern-crud.lisp` shows `:insert-rows-into` and a computed
  value again. It is written with literals ONLY today, deliberately: it was the
  program that found this, and it is the reason `examples/db/README.md` carries
  a "pass runtime values as statement parameters, not inside the S-SQL form"
  note in its Notes section. Both the example and that note are part of this
  fix -- drop the note in the same pass, or the workaround outlives the bug.
- `.kb/adjustable-arrays.md` gains the decision AND its reason (the
  re-evaluation trigger), including what `eq`/`eql` on a `make-string` result
  now answers per backend.

Code: `LispMacroExpander.expandMakeString` (~6118), `expandReplace` (~6174),
`expandScharSetFunctional` (~16119); the compile sites are
`JvmExprCompiler.compileCons` (~492) and `WasmExprCompiler.compileCons` (~706).
