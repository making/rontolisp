# `#=` (read-eval) in the Clojure reader

Difficulty: Medium

The Clojure front end's reader (`ClojureReader`) has no `#=` (nor `#.`) form; the
shared Lisp reader has the machinery (`LispLexer.ReadEvalMode`) but the Clojure
dispatch never gained it, and `doc/en/clojure/deviations.md` does not record the
omission either way.

## What to build

- `#=form` in a `.clj` source evaluates `form` when the reader reads it -- for
  rontolisp that means on the compile host -- and the result stands as the
  datum, so a compiled artifact (JVM class, war, WASM module/component) carries
  the RESULT as a literal, not the form.
- Evaluation at read time must reuse the same evaluator the front end already
  runs at macroexpansion time (`#=` before macros see the form; a `#=`
  result may be any Lisp value, printed nowhere).
- The read-eval environment: vars defined above the `#=` site in the same
  file, `*read-eval*` honored (true by default; `nil` => reading `#=`
  signals). Below-the-site and cross-file references are out of scope unless
  cheap.
- Pin behavior identical on every backend the front end serves: since the
  evaluation happens at compile time, the artifact is the same octets -- a
  ci-spec case or twin test asserting the embedded literal, plus one where
  `*read-eval*` nil refuses.
- Document in `doc/en/clojure/syntax.md` (reader forms) +
  `doc/en/clojure/deviations.md` if any edge diverges from the oracle
  (CLHS `*.=**` is not the oracle here; Clojure's `#=`, `(read-string "#=(...)")`
  semantics are), mirrored to `doc/ja/` in the same commit.

## Non-goals

- `#.` (CL sharpsign dot) in Clojure sources: the front end spells the same
  idea `#=`; adding both spellings is noise unless trivial.
- Read-eval inside data readers or reader conditionals beyond what falls out.

## Context

Motivated by `examples/clojure/serve-html.clj`: the capability-free
build-time-embedding story is carried by string-literal `io/resource` today;
`#=` would let a source embed ANY computed literal (not only files on the
source path) at compile time, matching `net/serve-html.lisp`'s `#.`
shape one-to-one.
