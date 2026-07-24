# A raw NUL byte in `WasmLispCompiler.java` makes `grep` silently skip the file

`src/main/java/am/ik/rontolisp/codegen/wasm/WasmLispCompiler.java` contains 13
literal NUL bytes. `grep` classifies the file as binary and, when its output is
piped, prints nothing at all -- no match, no warning, exit status 1. Every
`grep -rn <symbol> src/` over the tree therefore reports that the 4,330-line
wasm-GC driver does not mention the symbol, when it does.

It is the only file in the repository with this property (checked across every
`git ls-files '*.java'`).

## Symptoms

    $ grep -n 'TYPE_FLOAT' src/main/java/am/ik/rontolisp/codegen/wasm/WasmLispCompiler.java
    $ echo $?
    1
    $ rg -a -n 'TYPE_FLOAT' src/main/java/am/ik/rontolisp/codegen/wasm/WasmLispCompiler.java | head -1
    608:	static final int TYPE_FLOAT = 7; // in rec group - {f64 value}

`rg` reports `binary file matches` interactively but drops the file from piped
output as well; `-a` is needed either way. Anything that shells out to a plain
`grep` -- a script, an editor's project search, a CI check -- is blind to this
file by default.

## Root cause

The import-slot map keys module and field into one string with a NUL separator,
written as a literal NUL character in the source instead of the `\0` escape:

    importSlotIndex.putIfAbsent(decl.module() + "<NUL>" + decl.field(), ...)

Seven such key constructions and six matching lookups, at
`WasmLispCompiler.java:1763-1814` and `:1882-1926`. The separator itself is
fine -- a NUL cannot appear in a WASM import module or field name, so it is a
sound key separator. Only its spelling is the problem.

## Fix

Replace each literal NUL with the escape `"\0"`, or better, give the key one
name so the separator is stated once:

    private static String importSlotKey(String module, String field) {
        return module + '\0' + field;
    }

Purely mechanical: the compiled string is byte-identical, so every artifact is
unchanged. Guard it with a test that no source file under `src/` contains a raw
NUL byte, so the next one is caught at build time rather than by a developer
wondering why a search came back empty.

## Verification

- `./mvnw test` (the emitted bytes must not move -- the component byte-identity
  assertions in `WasmExportCompilerTest` / `WitExportInlinerTest` cover it).
- `grep -c 'TYPE_FLOAT' <file>` returns a count instead of nothing.
- The new no-raw-NUL test fails when a literal NUL is reintroduced.
