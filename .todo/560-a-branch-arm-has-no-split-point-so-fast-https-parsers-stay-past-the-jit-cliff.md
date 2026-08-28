# 560. A branch arm has no split point, so fast-http's parsers stay past the JIT cliff

Difficulty: High (splitting a BRANCH means a `go`/`return-from` leaving the arm
has to stop being a jump -- either an AST-level `flet` the cross-lambda exit
lowering already rewrites, or a state machine the enclosing method dispatches
over)

`.kb/hot-path-method-size.md` has one shape left with no split point, and two
methods in it, in the compile of `examples/net/httpbin-ningle.lisp`:

| method | bytecodes | cliff factor |
| --- | --- | --- |
| `FAST-HTTP.PARSER::PARSE-HEADER-FIELD-AND-VALUE` | 37,913 | 4.7x |
| `FAST-HTTP.MULTIPART-PARSER:HTTP-MULTIPART-PARSE` | 30,509 | 3.8x |

`JvmLibraryMethodSizeTest.noCompiledClackNingleMethodCrossesHotSpotsHugeMethodLimit`
names both with the size they compile to today, so they cannot GROW. Closing
this item is deleting that allowance.

## What they are (measured, not guessed)

NOT the `tagbody` state machines they read as. `proc-parse`'s `match-i-case`
generates a decision TREE over the header bytes -- one `if` per character
position per spelling, case-insensitively -- and duplicates the whole
`handle-otherwise` continuation (scan to the colon, skip the spaces, parse the
value) at every one of them. `parse-header-field-and-value`'s expanded body is
9,801 AST nodes holding 143 `if`s, 141 `go`s and only **3** tagbodies, and the
bytes are spread evenly: `if` 4,914 exclusive, `setq` 4,901, `=` 3,224,
`<=` 3,161, `unwind-protect` 2,210, `let` 1,968 -- no single operator is 15% of
the method. There is nothing left to shrink; it has to be CUT.

`JvmBodyOutliner` cannot: the function's tail spine is
`(or (with-octets-parsing ...) (error 'eof))`, one item.

## What is already done and must not be redone

The per-site emission shrinking is finished (`.kb/hot-path-method-size.md`, "The
sequences we emit per site"): the `%hb-guard` landing pad, the
`atom`/`consp`/`listp`/`stringp`/`eq`/`eql` predicates and long quoted literals
are one method per class now, and `_lookup`'s segment budget is 6000. That took
every OTHER method of the clack/ningle compile under the cliff and the request
loop from 11.2 s to 6.2 s per 10,000 requests. These two are what is left, and
they cost NOTHING measurable there -- `-XX:-DontCompileHugeMethods` no longer
moves that benchmark. So this item is about the INVARIANT, not about a slow
program: it earns its keep when a program appears whose time really is inside a
method of this shape.

## Directions

1. **An AST-level outliner.** Rewrite an oversized sub-form `F` in place as
   `(flet ((%o$N () F)) (%o$N))` before `CrossLambdaExitLowering` runs; closure
   conversion then boxes the variables `F` assigns, the exit lowering rewrites
   the `go`/`return-from` that leave `F` into `%nlx-throw`, and the backend
   emits `F` as its own method. Backend-INDEPENDENT -- the WASM chunker has the
   same problem. Two things to get right: the pass must know which positions are
   EVALUATED (the misread that has already cost twice, see
   `LispMacroExpander.evaluatedClauseForms`), which argues for a whitelist
   walker over FULLY EXPANDED forms rather than a generic tree walk -- and the
   cost, one closure and one indirect call per execution of the arm, plus a
   throw for every exit that now crosses it.
2. **A JVM-level state machine.** Cut at tagbody labels instead: a run of tags
   becomes a method answering the next state, the enclosing method a dispatch
   loop over state numbers. Cheaper at run time (a static call, no closure) but
   it only helps a tagbody, and these two are not tagbody-shaped -- so direction
   1 is the one that closes this item.
3. Start by measuring again with `-Drontolisp.jvm.debug-method-sizes=true`: a
   library that lands between now and then may have added a third method of this
   shape, and the front end may have shrunk these two further.

## Acceptance

- `CLACK_SHAPES_WITHOUT_A_SPLIT_POINT` deleted from `JvmLibraryMethodSizeTest`,
  the guard passing over the clack/ningle compile with no by-name allowance.
- `ExamplesE2eTest` output byte-identical, and the request-loop benchmark in
  `.kb/hot-path-method-size.md` no worse than 6.2 s -- an outlined arm that turns
  a hot `return-from` into a throw can easily cost more than the cliff it
  removes.
