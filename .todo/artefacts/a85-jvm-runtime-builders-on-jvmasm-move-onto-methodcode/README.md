# Moving an emitter onto `MethodCode`: the tools

The recipe is `.kb/jvm-method-size-limits.md`, "How a slice moves". Run from the repository;
`WORK` (default `/tmp/a85-work`) holds logs and outputs.

Converting:

- `mig.py [--asm NAME] [--asm-only] FILE...` -- the mechanical pass (supersedes a84's
  `migrate_asm.py`). `--asm NAME` for an assembler class named other than `JvmAsm`;
  `--asm-only` leaves the pool wrappers alone (a file whose raw code still uses them). Prints
  the lines left to do.
- `jc.sh` -- plain `javac` of `src/main/java` with every error, into `$WORK/jc.log`.
- `fix.py` -- reads `jc.log` and puts `.entry()` / `.methodRefEntry()` /
  `.interfaceMethodRefEntry()` where a wrapper meets an entry. Run `jc.sh` and `fix.py` in turns.
- `addto.py` -- rewrites the `definition.addMethod(..., x.maxStack(), x.maxLocals(), x.code(), ...)`
  calls `jc.log` reports into `x.code().addTo(definition, ...)`.
- `records.py FILE RECORD`, `records6.py FILE RECORD`, `handlers.py FILE RECORD 'stmt'...` --
  drop the declared sizes from a record's constructions; turn a handler table built from
  positions into `exceptionCatch` over bound labels.
- `imports.py FILE...`, `unused_imports.py [--fix] FILE...` -- the imports the new code needs /
  no longer needs.

Verifying (build the jar before and after, `./mvnw package -DskipTests`):

- `extract.py DIR` + `runchunks.sh A.jar B.jar DIR 12` -- every program in the JVM-side tests,
  compiled in process with both jars (`Cmp.java`), all class files compared; `rediff.sh`
  dumps the differing pairs and runs `MethodDiff.java` on them.
- `cmpcli.sh A.jar B.jar` -- the CLI programs (corpus at three levels, `mito-probe.lisp`,
  `jose-suite.lisp`, the examples). `cmpjars.sh` is a84's one-program form.

Expected differences: the build's commit id in version strings only.
