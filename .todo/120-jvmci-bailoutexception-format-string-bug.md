# Bug report to file upstream (OpenJDK): JVMCI passes an unescaped message as a format string to BailoutException

Status: ready to file (not filed yet). Self-contained: every reproducer below is
plain Java plus a stock JDK.

Affected code: OpenJDK's `jdk.internal.vm.ci` module
(`src/jdk.internal.vm.ci/share/classes/jdk/vm/ci/hotspot/`). It is plain OpenJDK
code, present in every JDK build we checked (see "Verified on"), not vendor-specific.

Where to file: JDK Bug System / https://bugreport.java.com, component
`hotspot/compiler` (JVMCI).

---

## Summary

`jdk.vm.ci.code.BailoutException(boolean permanent, String format, Object... args)`
runs its second argument through `String.format`. Two JVMCI call sites pass a
**pre-built message** as that argument instead of `("%s", msg)`. When the message
contains a `%` followed by a character that is not a valid format conversion, the
constructor throws `java.util.UnknownFormatConversionException` instead of the
intended `BailoutException`.

The message embeds the method being compiled, and `%` is a **legal JVM method-name
character** (JVMS 4.2.2 -- an unqualified name may contain anything except
`. ; [ /`). So any JVM language that mangles `%` into method names (Lisp/Scheme
compilers, generated internal helpers, etc.) can turn a *retryable* JIT bailout into
a *hard, non-retryable* compilation failure whenever a JVMCI compiler is in use.
Effects observed:

- the method silently never gets JIT-compiled (`COMPILE SKIPPED ... (not retryable)`),
- the JVMCI compiler's systemic-compilation-failure detector trips and prints
  warnings **into the application's stdout**, corrupting the program's output:

```
Warning: Systemic compilation failure detected: 1 of 49 (2%) of compilations failed
during last 0 ms [max rate set by SystemicCompilationFailureRate is 1%]. ...
```

## Verified on

| JDK | Reproducer A (the defect itself) | End-to-end JIT symptom |
| --- | --- | --- |
| Liberica JDK 25.0.3 (aarch64) | reproduces | not reachable: `No JVMCI compiler found` |
| Corretto JDK 17.0.16 (aarch64) | reproduces | not reachable: `No JVMCI compiler found` |
| a JDK 25.0.3 build shipping a JVMCI compiler | reproduces | reproduces (~2 of 3 runs of a 20 s workload) |

So the defective code is in stock OpenJDK; the end-to-end symptom additionally needs
a JVMCI compiler to be installed and enabled (`-XX:+UseJVMCICompiler`), because that
is what drives `HotSpotCodeCacheProvider.installCode`.

## Root cause

`jdk/vm/ci/code/BailoutException.java`:

```java
    public BailoutException(boolean permanent, String format, Object... args) {
        super(String.format(Locale.ENGLISH, format, args));   // <- format string
        this.permanent = permanent;
    }
```

Call site 1 -- `jdk/vm/ci/hotspot/HotSpotSpeculationLog.java:201` (the one we hit):

```java
                for (SpeculationReason reason : speculationReasons) {
                    byte[] encoding = encode(reason);
                    if (contains(failedSpeculations, newFailuresStart, encoding)) {
                        throw new BailoutException(false, "Speculation failed: " + reason);   // BUG
                    }
                }
```

`reason.toString()` embeds the method, e.g.

```
GuardMovement@5[HotSpotMethod<Prog.linalg::%la-matmul(Object, Object)>, 292, ClassCastException]
```

so `String.format` sees the conversion `%l` and throws
`UnknownFormatConversionException: Conversion = 'l'`.

Call site 2 -- `jdk/vm/ci/hotspot/HotSpotCodeCacheProvider.java:149` (same defect,
not yet observed in the wild; the message can carry a `%` from the installation
failure text):

```java
                String msg = hsCompiledNmethod.getInstallationFailureMessage();
                if (msg != null) {
                    msg = String.format("Code installation failed: %s%n%s", resultDesc, msg);
                } else {
                    msg = String.format("Code installation failed: %s", resultDesc);
                }
                throw new BailoutException(result >= config.codeInstallResultFirstPermanentBailout, msg);  // BUG
```

(Line 151 in the same method does it correctly: `new BailoutException("Error installing %s: %s", name, resultDesc)`.)

Observed failure, with the compiler-internal frames elided -- the interesting part is
that the exception is born inside JVMCI, on the install path:

```
Compilation of Prog.linalg$colon$colon%la-matmul(Object, Object) @ 292 failed:
    java.util.UnknownFormatConversionException: Conversion = 'l'
	at java.base/java.util.Formatter$FormatSpecifier.conversion(Formatter.java:3069)
	at java.base/java.util.Formatter.parse(Formatter.java:2814)
	at java.base/java.lang.String.format(String.java:4496)
	at jdk.vm.ci.code.BailoutException.<init>(BailoutException.java:66)
	at jdk.vm.ci.hotspot.HotSpotSpeculationLog.getFlattenedSpeculations(HotSpotSpeculationLog.java:201)
	at jdk.vm.ci.hotspot.HotSpotCodeCacheProvider.installCode(HotSpotCodeCacheProvider.java:121)
	... (JVMCI compiler frames: Backend.createInstalledCode -> CompilationTask.installMethod -> ...)
```

Note what is lost: the intended exception was a **non-permanent** bailout
(`permanent = false`, i.e. "a speculation failed, just retry"). The
`UnknownFormatConversionException` that replaces it is reported as
`COMPILE SKIPPED: ... (not retryable)`.

## Reproducer A (deterministic, 15 lines, no JIT and no JVMCI compiler needed)

Reproduces the defective formatting exactly as JVMCI performs it. Runs on any JDK
that ships the `jdk.internal.vm.ci` module (all of the above do).

```java
// BailoutFormatDemo.java
import jdk.vm.ci.code.BailoutException;

public class BailoutFormatDemo {

	public static void main(String[] args) {
		// The string HotSpotSpeculationLog.getFlattenedSpeculations() builds at line 201.
		String reason = "GuardMovement@5[HotSpotMethod<Prog.linalg::%la-matmul(Object, Object)>, 292, ClassCastException]";
		try {
			throw new BailoutException(false, "Speculation failed: " + reason);
		}
		catch (RuntimeException e) {
			System.out.println("thrown: " + e.getClass().getName() + ": " + e.getMessage());
		}
	}

}
```

```bash
javac --add-modules jdk.internal.vm.ci \
      --add-exports jdk.internal.vm.ci/jdk.vm.ci.code=ALL-UNNAMED BailoutFormatDemo.java
java  --add-modules jdk.internal.vm.ci \
      --add-exports jdk.internal.vm.ci/jdk.vm.ci.code=ALL-UNNAMED -cp . BailoutFormatDemo
```

Actual (Liberica 25.0.3, Corretto 17.0.16, and the JVMCI-compiler JDK alike):

```
thrown: java.util.UnknownFormatConversionException: Conversion = 'l'
```

Expected: a `jdk.vm.ci.code.BailoutException` whose message is the reason text.

## Reproducer B (end-to-end: a class with a `%` in a method name)

`%` cannot be written in Java source, so produce it by patching the single UTF-8
constant that carries the method name (both the `method_info` and the call site's
`NameAndType` point at it, so one replacement renames both). Both names are 8 bytes,
so no offsets move.

```java
// Victim.java
public class Victim {

	// Renamed to "hot%loop" by Patch below.
	public static long hotXloop(long n) {
		long s = 0;
		for (long i = 0; i < n; i++) {
			s += (i * i) % 7 + (s ^ i) % 13;
		}
		return s;
	}

	public static void main(String[] args) {
		System.out.println("result=" + hotXloop(2_000_000_000L));
	}

}
```

```java
// Patch.java
import java.nio.file.Files;
import java.nio.file.Path;

public class Patch {

	public static void main(String[] args) throws Exception {
		Path p = Path.of(args.length > 0 ? args[0] : "Victim.class");
		byte[] b = Files.readAllBytes(p);
		byte[] from = "hotXloop".getBytes("UTF-8");
		byte[] to = "hot%loop".getBytes("UTF-8");
		int hits = 0;
		outer: for (int i = 0; i + from.length <= b.length; i++) {
			for (int j = 0; j < from.length; j++) {
				if (b[i + j] != from[j]) {
					continue outer;
				}
			}
			System.arraycopy(to, 0, b, i, to.length);
			hits++;
		}
		if (hits != 1) {
			throw new IllegalStateException("expected exactly 1 occurrence, found " + hits);
		}
		Files.write(p, b);
		System.out.println("patched " + p + ": hotXloop -> hot%loop");
	}

}
```

```bash
javac Victim.java Patch.java
java Patch.java Victim.class          # hotXloop -> hot%loop
javap -p Victim.class | grep hot      # public static long hot%loop(long);
java -XX:+PrintCompilation -cp . Victim | grep 'hot%loop'
```

This shows the `%` name is legal, loads, runs, and JIT-compiles normally:

```
25  207 %     3       Victim::hot%loop @ 5 (44 bytes)
29  209 %     4       Victim::hot%loop @ 5 (44 bytes)
```

The name **alone** is not enough to trigger the bug. The full trigger additionally
needs (a) a JVMCI compiler in use, and (b) a speculation that the in-flight
compilation of that method relies on to **fail while that compilation is in flight**
-- the window between `HotSpotSpeculationLog.getFlattenedSpeculations()` being called
at code-install time and the speculation set being refreshed. In our case that was a
speculative guard-movement / `ClassCastException` speculation inside a hot numeric
loop. That race is what makes it timing-dependent (roughly 2 of 3 runs of a ~20 s
workload on an otherwise idle machine).

We could not distill that race into a small synthetic Java program (shapes tried:
cold-branch speculation, monomorphic call-site type-checked inlining, loop-invariant
`checkcast` guards, a boxed-`Object` matrix product with `Long`/`Double` type churn,
each with fresh class loaders and a sweep of poisoning delays -- none produced a
single `Speculation failed` bailout). Since Reproducer A pins the defect exactly, a
JVMCI-level unit test on `HotSpotSpeculationLog` with a `SpeculationReason` whose
`toString()` contains `%` is presumably the cheapest regression test.

For anyone wanting the end-to-end symptom on a real workload: use a JVM-language
compiler that mangles `%` into method names, run a hot numeric loop under a JVMCI
compiler, and diagnose with

```bash
java -XX:+PrintCompilation -cp . Prog   # names the skipped compile
#  286  311 %  4  Prog::linalg$colon$colon%la-matmul @ 292 (440 bytes)
#     COMPILE SKIPPED: java.util.UnknownFormatConversionException: Conversion = 'l' (not retryable)
```

plus the JVMCI compiler's "print compilation failures" option (option names are
compiler-specific) for the full stack trace.

## Suggested fix

Pass the message as an argument, never as the format string:

```java
// HotSpotSpeculationLog.java:201
throw new BailoutException(false, "Speculation failed: %s", reason);

// HotSpotCodeCacheProvider.java:149
throw new BailoutException(result >= config.codeInstallResultFirstPermanentBailout, "%s", msg);
```

Optional hardening: `BailoutException` could catch `IllegalFormatException` from
`String.format` and fall back to the raw format string, so a malformed message can
never escalate a retryable bailout into a permanent compilation failure.

## Impact

- Any JVM language emitting `%` in method names (an idiomatic "internal helper"
  marker in Lisp-family languages) can hit it under a JVMCI compiler.
- The failure is silent and non-retryable: the hot method stays interpreted (a large
  performance cliff), and the JIT warning text is written to the application's stdout,
  which breaks any output-comparison test around that program.
- A `%` followed by a valid conversion character (e.g. `%s`, `%d`) would not throw
  here but would still mis-format (or throw `MissingFormatArgumentException`), so the
  problem is not limited to `%l`.

## Workaround (for language implementers)

Do not emit `%` in generated method names -- map it to something like `$pct` in the
compiler's name mangler. Renaming the affected internal method took the failure from
roughly 2 of 3 runs to 0 of 5, with byte-identical program output.

Alternatives that only mask the symptom: set the JVMCI compiler's systemic-failure
rate to 0 (silences the detector; the method is still never compiled), or disable the
JVMCI compiler (`-XX:-UseJVMCICompiler`), falling back to C2.

## How it surfaced

An output-comparison test of a Lisp-to-JVM-bytecode compiler failed on one case: the
program's own output was byte-identical to the expectation, but two JIT warning lines
had been injected into stdout. The hot method was a generated internal matrix-product
helper whose mangled JVM name contained `%l` (`linalg::%la-matmul`, an internal name
in that language's numeric library).
