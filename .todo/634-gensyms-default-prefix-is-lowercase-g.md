# `gensym`'s default prefix is lowercase `g`, so every printed macroexpansion diverges

Difficulty: Low

Split out of `.todo/156` (2026-09-02), whose remaining axis is the deferred A1
intern table -- this is a separate one-word conformance miss that A1 does not
need.

CL's `gensym` names its symbols with an uppercase `G` prefix. Ours uses `g`,
in three places that must agree:

- `src/main/java/am/ik/rontolisp/eval/Environment.java` (~line 3359)
- `src/main/java/am/ik/rontolisp/codegen/jvm/JvmGensymCompiler.java` (~line 50)
- `src/main/java/am/ik/rontolisp/codegen/wasm/WasmGensymCompiler.java` (~line 34)

The lowercase `g` is observable, not cosmetic. Since `.todo/626` landed, a
symbol named `"g3"` must be `|...|`-escaped to read back and one named `"G3"`
must not, so a printed macroexpansion diverges from SBCL on every gensym:

```lisp
;; chapter 8 of the Practical Common Lisp corpus, `ppme`
;; rontolisp: (LET ((|g3| 0) ...))
;; SBCL:      (LET ((G119 0) ...))
```

## What to watch

- `gensym`'s counter is shared with `gentemp`/internal expander gensyms if
  those exist; check what else spells a `g` prefix before changing one site.
- Test expectations across the suite that pin a gensym's printed name, and any
  `.kb`/doc page that shows one. The prefix appears in macroexpansion output in
  `doc/{en,ja}`, so `DocExamplesTest` with `-Drontolisp.doc.fix=true` is part
  of the change.
- Programs that build a symbol name from the prefix (a `(gensym "PREFIX-")`
  caller is unaffected; only the DEFAULT changes).
- All four backends must agree -- the three sites above are the interpreter,
  the JVM and both WASM backends.

## Verify

`(gensym)`, `(gensym "FOO-")`, and chapter 8's `ppme` output against SBCL
2.2.9 on all four backends; update the corpus table in `.kb/asdf.md`.
