# k10. Playground: a Java program with threads freezes the browser

Difficulty: High

Reported by the user (2026-11-19): running this example of `doc/en/java/index.md` (the
`ExecutorService` / `CompletableFuture` one, around line 603) in the browser freezes the
browser tab. The same program runs on the CLI's four backends (it is a doc example,
`DocExamplesTest`).

```java
void main() {
    Map<String, Integer> lengths = new ConcurrentHashMap<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
        for (String word : List.of("one", "three", "seven")) {
            pool.execute(() -> lengths.put(word, word.length()));
        }
    }
    CompletableFuture<Integer> total = CompletableFuture.supplyAsync(() -> lengths.get("three"))
        .thenApply(n -> n + lengths.size());
    IO.println(lengths + " " + total.join());
}
```

## What is known

- The playground is the interpreter built by `-Pweb` (GraalVM `svm-wasm`) and "runs no
  threads" (`.kb/interpreter-stack.md`); it substitutes `AsyncRuntime`
  (`src/web/java/.../Target_AsyncRuntime.java`). The interpreter leg of `java.lisp` runs Java
  threads on host threads, so the likely shape is a wait (`close`, `join`) for a thread that
  never runs, spinning on the page's only thread. Not yet reproduced or measured.
- The wasm backends run Java threads on one stack since k00 (`.kb/java-frontend.md`,
  "Resumable bodies": `JavaResumable` + the scheduler in `java.lisp`'s wasm leg).

## Leads

1. Reproduce: `./mvnw -Pweb -DskipTests package`, serve the docs site, run the cell
   (`.kb/documentation-site.md`). Find where it spins. Check the other thread examples of
   `doc/*/java/` and the Clojure/CL thread examples too (`future`, `bt:make-thread`).
2. Fix the cause, not the symptom: a Java program should run in the playground with the
   output it has elsewhere. Candidates: the playground lowering Java threads the way the wasm
   backends do (resumable bodies on one stack), or a real scheduler behind the substituted
   runtime. Weigh by what each costs the playground's wasm size and the other front ends.
3. Independently, a program that never ends must not hang the page: running a cell off the
   page's thread (a Web Worker) with a stop control, or a step budget that ends the run with
   a message. Measure before choosing.
4. Where something stays unsupported in the playground, the cell says so in words instead
   of freezing.

## Done when

- The example above prints in the playground what it prints on the CLI; the doc's other
  thread examples do too, or say why not.
- A regression test runs the playground's Java pick over a threaded program, if a playground
  test can reach it (`PlaygroundReplTest` runs the REPL on the JVM, not in the browser: say
  which part it cannot cover).
