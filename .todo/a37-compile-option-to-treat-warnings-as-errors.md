# A compile option that fails the compile on a compile-time warning

Difficulty: Medium

A static wrong-count call is no longer a compile error:
- For a built-in, `compiler/BuiltinCallArity` made it a run-time `program-error` plus a compile-time warning.
- For a user function or lambda form, `compiler/DefinedCallArity` did the same.

This is correct ANSI behavior, the same as SBCL. The cost is that a user who misses the warning ships the bug.

Add an opt-in option that turns compile-time warnings into a failed compile, like SBCL's `failure-p` or `-Werror`. The default stays as it is.

## Design points to decide

**Name.** For example `--warnings-as-errors`. Check the CLI's existing option naming first.

**Where the option is exposed.** Every compile path takes it:
- The CLI `-o` for `.class`, `.jar`, `.wasm`, `--component` and `--native`.
- `cli/JvmSourceCompiler` for embedders.
- The Maven plugin, as a parameter.

**Which warnings count.** Every warning goes through `compiler/CompileWarnings` (`warn`, `warnStaticProgramError`, and the attempt buffering in `startAttempt`, `flushAttempt` and `discardAttempt`).
- Decide whether all of them count, or only the static-program-error class.
- Undefined-function warnings are a candidate too.
- Library-internal warnings must not fail a user compile. `CLAUDE.md` mentions the `TOKENIZER:... is undefined` noise as a precedent.
- A warning from a discarded attempt must not count. A backend may compile the same code twice.

**How it fails.**
- Print every warning, not only the first.
- Exit non-zero.
- Write no output file.

**Interpreter / `run`.** Decide whether the option applies there. It probably does not, because the interpreter does not warn.

## Verification

- Unit tests: a wrong-count call to a user function and to a built-in each fail the compile under the option and compile without it.
- A discarded-attempt warning does not count.
- Document the option in `doc/en` and `doc/ja`: the CLI reference, plus the mention in `defun.md`.
- Update the invariant in `.kb/error-handling.md` (the wrong-count sections).
