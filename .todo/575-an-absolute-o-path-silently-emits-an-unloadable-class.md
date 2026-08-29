# 575. An absolute `-o` path silently emits an unloadable class

Difficulty: Low

`-o com/acme/Kernels.class` deliberately reads the directory as the class's PACKAGE
(`RontoLispCli`, "the class name is the -o path with .class taken off"). An ABSOLUTE
path goes through the same rule and produces a name no JVM will load, with no error at
compile time:

```bash
java -jar target/rontolisp-0.1.0-SNAPSHOT-exec.jar prog.lisp -o /tmp/out/T2.class
javap /tmp/out/T2.class | head -1
# public class .tmp.out.T2 {
cd /tmp/out && java T2
# Error: LinkageError occurred while loading main class T2
#   java.lang.ClassFormatError: Illegal class name "/tmp/out/T2" in class file T2
```

The leading `/` becomes an empty package segment, and any directory that is not a valid
Java identifier (`-home-administrator`, a uuid with dashes) becomes an illegal one. The
compile SUCCEEDS and prints nothing; only running the artifact reports it. `--class-name`
is the workaround, and CLAUDE.md's manual-verification recipe uses a path-free
`-o Prog.class`, which is why no test sees it.

Found 2026-08-29 while measuring `.todo/573`: a before/after emitted-bytes comparison
that compiled into two different temp directories showed EVERY output differing, and the
whole difference was the baked-in directory. A harness reads that as a regression, which
is what makes the silence expensive.

## The fix to weigh

At the point that derives the name from the `-o` path, validate the segments: a package
name derived from a path is only a package name when the path is RELATIVE and every
segment is a valid Java identifier. Otherwise either use the file name alone (which is
what a path-free `-o` already produces and what `java -cp <dir> Name` loads) or refuse
with a message naming `--class-name`. Refusing is the safer half of the choice for a
path that looks like it meant a package; falling back is the friendlier one for an
absolute path, where a package was never plausible.

## Acceptance

- `-o <absolute dir>/Name.class` either emits a class `java -cp <dir> Name` runs, or
  fails at compile time pointing at `--class-name`. A test compiles into a `@TempDir`
  and reads the emitted name back.
- `-o com/acme/Kernels.class` still names `com.acme.Kernels`, and `--class-name` is
  unchanged.
