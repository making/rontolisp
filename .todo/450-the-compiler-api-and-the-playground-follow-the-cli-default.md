# 450. The compiler classes and the playground follow the CLI's default

Difficulty: Medium

Child of `.todo/448`; do `.todo/449` first. That item flips the CLI. This one
makes the flip a property of the COMPILER rather than of one frontend, so there
is a single answer to "what does rontolisp do by default".

## The divergence 449 leaves behind

The level-less constructors hard-code `OptimizeLevel.NONE`:

- `codegen/jvm/JvmLispCompiler(String)` and `(String, boolean)` -> `NONE`
- `codegen/wasm/WasmLispCompiler()`, `(boolean)`, `(boolean, boolean)`,
  `(boolean, boolean, boolean)` -> `NONE`
- `codegen/wasm/NoGcWasmCompiler()` -> `NONE`

The browser playground calls exactly those: `RontoPlayground.compileJvm` uses
`new JvmLispCompiler(name)` and `compileWasm` uses `new WasmLispCompiler()`.
So after 449 the CLI hands a user a 532-byte hello-world module and the
playground's "compile to WASM" button hands the same user 156,641 bytes of the
same program. The playground has no CLI and no flags -- it cannot be told to
optimize -- so this is not a default anyone can override there.

## The change

Flip those constructors to `OptimizeLevel.DEFAULT` and make every caller that
genuinely wants the unoptimized shape say `OptimizeLevel.NONE` out loud.

This is a breaking change for an embedder, and it is the right one: the level
is what the artifact IS, and a caller who does not name one should get what the
project's own frontend gives. Say so in the release note rather than keeping
two answers.

Do NOT delete the level-less overloads to "force the question" -- ~135 call
sites in the test tree use them, most of which do not care about the level at
all, and the churn would bury the sites that do.

## Where the work is

Roughly 135 call sites use a level-less constructor, essentially all in
`src/test/java`. They split three ways and each needs a decision, not a
sweep:

- **does not care** (compiles a program to assert it compiles, or runs it and
  asserts the output) -- leave it, and let it now run shaken. This is the
  coverage this item is worth having for: `JvmLispCompilerTest`,
  `WasmLispCompilerTest`, `WasmImportCompilerTest`, `WitExportInlinerTest`,
  `WasmHostGlueE2eTest` and friends collectively compile a large corpus that
  has only ever been asserted unshaken.
- **asserts on the unoptimized shape** (a function/method/import/type is
  PRESENT; a byte-identity pair; a "before" size) -- must say
  `OptimizeLevel.NONE`. `WitExportInlinerTest.assertByteIdentical`,
  `WasmTreeShakerTest`/`WasmTreeShakerCorpusTest` and their
  `JvmClassShaker*` twins already pass explicit levels and need nothing;
  the risk is the ones that rely on the constructor's default without saying
  so.
- **breaks under the shaker** -- a first-class finding, not a test to pin at
  `NONE`. See the parent's "corpora that have never run shaken".

`am.ik.jvm`/`am.ik.wasm` are language-independent and take the level from their
caller; nothing changes there.

## Watch

- `WasmLispCompiler`'s internal `Ctx.optimize` and the nested builder's field
  both default to `NONE` (~L7090, ~L7663). They are overwritten by every
  constructor and only ever asked `prefersSizeOverSpeed()`, which `NONE` and
  `DEFAULT` answer alike -- so they are inert either way. Set them to `DEFAULT`
  anyway so a future reader does not infer a second default from them.
- `src/web/java` compiles only under `-Pweb`, so `./mvnw test` will not catch a
  break there. Run `./mvnw -Pweb compile` after the test suite (and `clean`
  before the next `./mvnw test`).
- The playground shows the compiled artifact's size to the user. Confirm by
  hand that its hello-world drops to the CLI's number; that is the check that
  says this item actually landed.

## Acceptance

`./mvnw test` green, `./mvnw -Pweb compile` green, and the playground's
compiled hello-world is the same size as the CLI's. Every test that now says
`OptimizeLevel.NONE` says it because it asserts on the unoptimized shape, not
because it failed shaken.
