# `--dump-ir`: the IR of one compile (2026-11-24, m04)

**Invariant: `--dump-ir` is a compile that stops after `CompileFrontend.run` and prints the
program it would hand its backend; the target is the one `-o` and its flags name, and
nothing is written.** User docs: `doc/en/compiling/ir-dump.md`.

- **The premise it replaced** (2026-09-30, `ab2386d86`): "the dump happens before any backend
  is chosen, so it is the same for every target; `-o` is refused". Measured false
  (2026-11-24): the front end reads with the target's `Features` and lowers for it, so the old
  dump, run with no target flag, was always the `.class` compile's IR -- `#+rontolisp-jvm`
  read, `#+rontolisp-wasm` dropped. What differs by target: the features (`rontolisp-jvm` /
  `-wasm` / `-reactor` / `-servlet` / `-component` / `-body-imports` / `-native`), the library
  splices keyed on `WitExportDirective.Backend`, `ExitLibrary`'s reactor arms, `JavaLibrary`'s
  variant, and a Java program's entry (`JavaLowering.exportEntries`: a class with no `main` is
  l94's reactor on `--no-wasi` alone, l85's `@WitExport` world on `--component` alone, refused
  elsewhere). Any front end is affected, not only Java's: of the 17 `examples/clojure/*.clj`, 15 dump
  differently for `.class` and `-o x.wasm`. `examples/java/http/Server.java`
  dumps five different IRs: `.class` 704 lines / 267,116 B, `--no-wasi` 776 / 287,379,
  `--no-wasi --host-fetch --host-boundary=streaming` 780 / 287,955, `--native` 785 / 290,204,
  `--component` 1,829 / 329,654.
- **Where**: `RontoLispCli.run` routes `--dump-ir` into `compileToFile` with the `-o` name, or
  without one `dumpTarget`'s: `--native` a native name, a flag only a `.wasm` output takes
  (`--no-wasi`, `--component`, `--no-gc`, `--host-*`, `--reentrant`, `--report-locations`,
  `--emit-wit`, `--emit-js-glue`, `--emit-worker-entry`) a `.wasm`, else a `.class` (the old behavior, so
  `ClojureNativeLoweringE2eTest`'s dumps are unchanged). `compileRecorded` runs every refusal
  the compile runs, skips the native shim's load, and returns after the front end
  (`--warnings-as-errors` fails it before anything prints). No second options builder: the
  dump cannot drift from the compile it names. A flag that changes no front-end input
  (`--host-output`, `--host-random`, `--optimize`, `--class-name`) is accepted and changes
  nothing, as on the compile it names.
- **A no-`main` Java class with no target flag stays refused**, the `.class` compile's
  refusal; dumping it as the reactor it can only be would pick a target the command line
  did not name. The refusal already names `-o out.wasm --no-wasi`, which the dump now takes.
- **Compiles unchanged** (2026-11-24, byte-compared against the base jar): `Calc.java
  --no-wasi` 1,307 B, `Server.java --no-wasi --host-output` 585,788 B, the `@WitExport`
  `Counter` component 8,164 B, `Server.java -o X.class` 389,570 B.
- **Pinned by** `RontoLispCliTest.dumpIrPrintsTheIrOfTheCompileItsFlagsName` (the four
  features across no flag, `-o .wasm` not written, `-o .war`, `--no-wasi`; a compile's
  refusal; the Java reactor and its refusal) and the other `dumpIr*` tests (the round trip).
