# `ClMustacheE2eTest`'s four legs write one fixed path, and race under full-suite load

Difficulty: Low (the diagnosis is done and reproduced; the work is threading a per-leg
name through a base class four other subclasses share)

Found 2026-09-13 while landing the single-call-site move, in a full `./mvnw test`:

```
compilesAndRunsOnWasmPreview1 / compilesAndRunsOnWasmComponent
  expected "file says hi"
  actual   "file says hifile says hi"
```

Green on the next three runs of the class alone, and green in the full run before it.

## What it is

`ClMustacheE2eTest`'s exercise program writes and reads one hard-coded path:

```lisp
(with-open-file (s "target/mustache-e2e.mustache" :direction :output :if-exists :supersede)
  (write-string "file says {{who}}" s))
```

`AsdfLibraryE2eSupport` is `@Execution(ExecutionMode.CONCURRENT)` and runs that same
program on four backends. The two WASM legs run in **one shared wasmtime container**, so
they share a filesystem and both write that path at once; the interpreter and JVM legs
share the host's. A reader can therefore see the file empty, half-written, or -- as here --
carrying two writes.

## It is not backend-specific, and not the compiler's

Reduced to a program with no mustache in it (`with-open-file` out, read back, `princ`),
compiled to wasm and run four at a time from one directory, five rounds: **2 of 20 runs
printed nothing at all**. The same probe built with the pre-change jar: 1 of 20. So this is
the work file, not the emitter -- and it has presumably been latent since the leg was
written.

## The fix

The name has to differ per leg. The token is not available where the program is: the
program is a constant on the subclass and `AsdfLibraryE2eSupport` does not hand its legs
anything that distinguishes them. So either

- give the base class a per-leg substitution (a `%%WORK%%` token in `exercise()`, replaced
  with `interpreter`/`jvm`/`wasm-p1`/`wasm-component` by the leg that is about to run), which
  is the one that generalises -- any future subclass touching the filesystem gets it; or
- run each WASM leg in its own container working directory.

Whichever lands, it must leave the other four `AsdfLibraryE2eSupport` subclasses
(split-sequence, parse-number, cl-utilities, cl-who) building the same programs they build
now -- none of them writes a file today, which is why only this one shows the race.
