# The AppKit-through-FFM feasibility spike (2026-08-25)

Throwaway probes kept for reproducibility, NOT project code: they are outside `src/`, are
not in the reactor, are not formatted by `spring-javaformat:apply`, and nothing builds or
tests them. They exist so the four claims in
`../512-a-native-macos-gui-from-the-repl-through-ffm.md` can be re-run on another Mac.

Machine: Apple M4 Max, macOS 26.3.1, Oracle GraalVM 25.0.3 (aarch64). Nothing here needs
Xcode, a bundle, or any dependency -- AppKit, `libobjc` and libdispatch are in the OS.

| file | what it answers |
|---|---|
| `AppKitSpike.java` | the binding: window, label, target/action button, and the main-thread pump. Static methods only so a probe outside `src/` can be reached from Lisp at all -- the feature itself ships a `gui` package and no `java:` |
| `appkit-spike.lisp` | the same window driven from the rontolisp interpreter, with the button handler written in Lisp |
| `NativeImageSpike.java` | the native-image case: `main` IS thread 0, the REPL moves off it, thread 0 idles in `CFRunLoopRun` |
| `reachability-metadata.json` | the `downcalls` + `upcalls` registration `NativeImageSpike` needs. The `upcalls` section is the shape the project does not have yet |

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

## The two traps these probes exist to remember

- **`dispatch_sync` to the main queue from the main thread kills the process.** A button
  handler already runs on thread 0; the Lisp it runs calls back into the GUI. `onMain` in
  `AppKitSpike.java` tests `pthread_main_np()` and runs the body inline -- without that
  line the first click took the whole process down.
- **A `findStatic` whose method name is a variable does not survive native image.**
  `NativeImageSpike` folds two constant lookups instead; the variable-name version threw
  `NoSuchMethodException` in the binary and worked fine on the JVM, which is the worst
  possible way for it to fail.
