# The AppKit-through-FFM feasibility spike (2026-08-25)

Throwaway probes kept for reproducibility, NOT project code: they are outside `src/`, are
not in the reactor, are not formatted by `spring-javaformat:apply`, and nothing builds or
tests them. They exist so the four claims in
`../512-a-native-macos-gui-from-the-repl-through-ffm.md` can be re-run on another Mac.

Machine: Apple M4 Max, macOS 26.3.1, Oracle GraalVM 25.0.3 (aarch64). Nothing here needs
Xcode, a bundle, or any dependency -- AppKit, `libobjc` and libdispatch are in the OS.

| file | what it answers |
|---|---|
| `AppKitSpike.java` | the binding: window, label, target/action button, and the main-thread pump. Static methods only so a probe outside `src/` can be reached from Lisp at all -- the feature itself ships an `objc` package and no `java:` |
| `appkit-spike.lisp` | the same window driven from the rontolisp interpreter, with the button handler written in Lisp |
| `NativeImageSpike.java` | the native-image case: `main` IS thread 0, the REPL moves off it, thread 0 idles in `CFRunLoopRun` |
| `reachability-metadata.json` | the `downcalls` + `upcalls` registration `NativeImageSpike` needs. The `upcalls` section is the shape the project does not have yet |
| `Encodings.java` | whether the ObjC runtime describes a selector well enough to DERIVE the `FunctionDescriptor` (it does, structs included) |
| `ShapeCensus.java` | how many distinct FFM shapes a real AppKit surface needs, which is what the native image must register ahead of time |

## Running them

The Lisp end (needs `target/rontolisp-0.1.0-SNAPSHOT-exec.jar`; from this directory):

```bash
javac -d /tmp/appkit-spike AppKitSpike.java
java --enable-native-access=ALL-UNNAMED \
  -cp ../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar:/tmp/appkit-spike \
  am.ik.rontolisp.cli.RontoLispCli appkit-spike.lisp
```

A window opens, `windowNumber = <id>` is printed, and two seconds later the program clicks
its own button so the run needs no human: the label becomes
`clicked 1 time(s) -- handler written in Lisp`, printed from the Lisp closure. Capture it
without a screen recording of the desktop with `screencapture -x -l<id> shot.png`.

The native-image end:

```bash
mkdir -p /tmp/ni/META-INF/native-image/spike && cp reachability-metadata.json $_
javac -d /tmp/ni NativeImageSpike.java
native-image --enable-native-access=ALL-UNNAMED -cp /tmp/ni -o /tmp/nativespike NativeImageSpike
/tmp/nativespike
```

It prints `java main pthread_main_np() = 1` -- the whole point: in a native image the Java
main thread is the process's first thread, which is the one AppKit demands, so the REPL
work is spawned and thread 0 is handed to `CFRunLoopRun`. Under `java -jar` the same call
answers `0`, because the launcher already did this for us.

## The naming evidence

`Encodings.java` and `ShapeCensus.java` are why the built-in package is `objc` and not
`gui` / `appkit` / `cocoa`. Run them with nothing but the source launcher:

```bash
java --enable-native-access=ALL-UNNAMED Encodings.java
java --enable-native-access=ALL-UNNAMED ShapeCensus.java
```

The first prints each selector's full type encoding, structs and all
(`{CGRect={CGPoint=dd}{CGSize=dd}}`) -- so a generic `objc:send` reads the signature off
the runtime instead of carrying a hand-written table. The second buckets every method of
25 core AppKit/Foundation classes by FFM shape: 9,932 methods, 440 distinct shapes, of
which the top 25 cover 86.1%, the top 50 cover 91.2% and the top 100 cover 94.7% -- the
number that decides whether a closed, AOT-registered shape set can serve a dynamic
`objc:send` in the native binary. On another macOS release the totals move; the SHAPE of
the distribution (a very short head of `id(id,SEL,id)`, `BOOL(id,SEL)`, `void(id,SEL,id)`,
`void(id,SEL)`) should not.

## The two traps these probes exist to remember

- **`dispatch_sync` to the main queue from the main thread kills the process.** A button
  handler already runs on thread 0; the Lisp it runs calls back into the GUI. `onMain` in
  `AppKitSpike.java` tests `pthread_main_np()` and runs the body inline -- without that
  line the first click took the whole process down.
- **A `findStatic` whose method name is a variable does not survive native image.**
  `NativeImageSpike` folds two constant lookups instead; the variable-name version threw
  `NoSuchMethodException` in the binary and worked fine on the JVM, which is the worst
  possible way for it to fail.
