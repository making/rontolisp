# 558. fast-http's parser state machines compile to 7x the JIT cliff

Difficulty: High (a `tagbody` body has no tail spine to split: every `go` names a
position in the frame it was emitted in, so this needs a different lowering, not
another split point)

Found while closing `.todo/526`, whose `JvmBodyOutliner` splits a function's tail
spine and cannot touch these. `examples/net/httpbin-ningle.lisp`,
`-Drontolisp.jvm.debug-method-sizes=true`:

| method | bytecodes | cliff factor |
| --- | --- | --- |
| `FAST-HTTP.PARSER::PARSE-HEADER-FIELD-AND-VALUE` | 56,513 | 7.1x |
| `FAST-HTTP.MULTIPART-PARSER:HTTP-MULTIPART-PARSE` | 32,183 | 4.0x |
| `LACK/UTIL:FIND-PACKAGE-OR-LOAD` | 12,557 | 1.6x |
| `PACKAGE-USE-LIST`, `%SBR-*`, `%MMI-*` | 9,600-11,100 | 1.2-1.4x |

Every one of them runs in the bytecode interpreter for the life of the process
(`.kb/hot-path-method-size.md`), and the first two are the whole hot path of
every HTTP request a `clack`/`ningle` program serves.

`parse-header-field-and-value` is a `tagbody` state machine (the macro-generated
`with-octets-parsing` expansion), which is ONE item to the body splitter, and
`tagbodyScopes` would decline it anyway: a `go` branches to a label in the same
method, so moving part of the body out breaks the branch.

## Directions, roughly in order of how much they change

1. **Outline a tagbody SEGMENT as a state function.** A run of tags with no
   backward `go` into its middle becomes its own method taking the live locals;
   a `go` that leaves the segment answers the next state instead of branching,
   and the enclosing method becomes a dispatch loop over state numbers. This is
   the general fix and the expensive one.
2. **Shrink the emission first.** Measure what those 56 KB actually are before
   restructuring: a `%SBR-*`/`%MMI-*` at 10 KB is generated dispatch, and if the
   per-case emission is what is large then making the case smaller helps every
   one of these rows at once (`.todo/557`'s bignum literals are exactly that
   kind of finding).
3. Establish whether the tax is real here at all -- `.todo/526` found the cliff
   cost SHA-512 nothing, because the interpreted body was 4% of the run. Time a
   request loop with `-XX:-DontCompileHugeMethods` BEFORE building anything.

## Acceptance

- `JvmLibraryMethodSizeTest`'s guard extended to a `clack`/`ningle`-loading
  compile, passing with no by-name exclusion.
- A request-loop benchmark on `-o Prog.class` improves by the factor step 3
  measured, and `ExamplesE2eTest`'s ningle examples stay byte-identical in
  output.
