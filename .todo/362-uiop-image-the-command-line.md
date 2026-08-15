# `uiop/image`: the command line on four backends

Difficulty: Medium

**The rest of `uiop/image` landed 2026-08-15**: exit (`quit` / `die` /
`shell-boolean-exit`, the host's own exit on all four backends), the
fatal-condition quartet, the three lite backtrace printers and the image hooks
(`dump-image` / `restore-image` / `create-image` name what is missing). Measured
coverage today (`UiopCoverageTest.printCoverage`, the authority): **25 / 30**.
Read `.kb/uiop.md`'s "`uiop/image`'s decisions" section first -- it carries the
reasons, and this item must not re-decide them.

What is left is the FIVE command-line exports, which are one unit: implementing
some of them leaves the others signalling from inside their own callers.

```
ARGV0 COMMAND-LINE-ARGUMENTS RAW-COMMAND-LINE-ARGUMENTS
SETUP-COMMAND-LINE-ARGUMENTS *COMMAND-LINE-ARGUMENTS*
```

`argv0`, `command-line-arguments`, `raw-command-line-arguments` and
`setup-command-line-arguments` signal `not-implemented-error` today;
`*command-line-arguments*` is the nil stub (which is also upstream's default).
This is how a script reads its arguments, and rontolisp has no other spelling of
that at all.

## The primitive: `%host-argv`

One new internal primitive beside `%host-getenv` / `%host-getcwd` /
`%host-exit`, answering the program's own argument vector as a list of strings,
and ONE Lisp definition of the five over it (`uiop-image.lisp`), exactly the way
`uiop:getenv` and `uiop:quit` are shared.

**Make the four backends answer the same SHAPE**, or the family is four
behaviours wearing one name. The proposal that came out of the exit work: the
vector is `(program-name user-arg ...)` everywhere --

| backend | where it comes from |
|---|---|
| interpreter | the CLI's own `String[] args`: the input file as argv0, then the arguments after a `--` separator. `CliOptions` currently REJECTS a second positional argument, so the separator has to be introduced there (`rontolisp app.lisp -- a b`), which is also the convention upstream's `command-line-arguments` documents for a non-executable image |
| JVM | `main(String[] args)` carries the user arguments and no argv0; the class NAME is what stood on the command line, so it is the honest argv0 to prepend |
| WASM Preview 1 | `args_sizes_get` / `args_get` -- the same buffer shape `_getenv` already scans (`WasmGetenvRuntimeBuilder`), so the helper is that one plus the list building `%list-directory` does |
| `--component` | `wasi:cli/environment@0.3.0`'s `get-arguments`, the sibling of the `get-environment` binding `environment.lisp` already carries |

Then `command-line-arguments` is `(rest raw)` on every backend and `argv0` is
`(first raw)`, with no per-backend arm -- which is the point. Upstream reaches
the same answer through `*image-dumped-p*` being `:executable`; it stays nil
here (nothing dumps), so the shared definition documents its own divergence
instead.

## The one hard part: the component's import block

`environment.lisp` binds `get-environment` **FROM the fixed import block**,
which declares that interface with THAT MEMBER ONLY -- `wasm-tools component
new` narrows an imported instance type to what `core.wat` imports. A second
user import of `wasi:cli/environment@0.3.0` would be an invalid duplicate name,
so `get-arguments` can only come from the block, and that means the regeneration
chain of `src/wasm-component/README.md`:

1. `core.wat` gains the `get-arguments` import, `regen.sh` rewrites
   `import-block.bin`;
2. `WasmComponentBuilder.FIXED_BLOCK_IFACES` gains the member (it is the list of
   what a `%component-import` may bind from the block);
3. rebuild the jar, `regen-wit.sh` refreshes the `--emit-wit` fixtures,
   `WasiWitDefinitionsGenerator` regenerates the Java model, reformat;
4. re-run `WasiWitDefinitionsTest`, `WitEmitterTest`, `WitOracleE2eTest`.

Note this WIDENS the base block for every non-`--optimize` component, unlike
`uiop:quit`'s `wasi:cli/exit`, which is an appended user import precisely
because the block does not declare that interface. Weigh a serve program too:
its own block declares no environment interface, so there `environment.lisp` is
an appended user import and both members come for free.

## Gate

`UiopCoverageTest` reports `uiop/image 30/30`. `ci-spec.yaml` gains a case
printing `(uiop:command-line-arguments)` with arguments passed on all four
backends -- the driver runs each backend with its own launcher, so the case also
pins that argument passing agrees, which nothing tests today; expect to teach
`CiSpecE2eTest` to pass arguments (it passes none). Docs: the "What is missing:
the command line" section of `doc/{en,ja}/reference/uiop/image.md` becomes the
family's own section, and `reference/uiop.md`'s coverage row moves to 30 / 30.
