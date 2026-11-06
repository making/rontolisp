# i73. coalton on the compile paths: the whole library, on all four

Difficulty: High

Rewritten 2026-11-06. The stops within the first minute are gone, and the first question has its answer
(`.kb/asdf.md`, "Libraries that load", coalton, "The compile paths"): a compile typechecks every coalton form
where it reads it, as SBCL's `compile-file` does, so it is a reasonable thing to do, and its cost is the
interpreter's speed at read time. Checked with the library's first five files: a program over them prints what
the interpreter and SBCL print on P1 and the component.

What is left:

- The JVM stops at fset's `compare` dispatcher under the generic-function protocol (`.todo/i77`).
- The whole library: the read-time image typechecks it as the interpreter does, an hour (`.todo/i75`), and the
  macro-time pass then replays its generated code. Unmeasured past five files: the compile's own time, the
  program's size, and whatever the later files stop on. Check file by file with a stand-in `coalton/library`
  over a prefix of its components (the derived forms key on the name and on `library/<file>` relative to the
  `.asd`, so link the real `library/` and `src/` beside it).
- Verified means the program `.kb/asdf.md` measured on the interpreter printing SBCL's answers on all four.
