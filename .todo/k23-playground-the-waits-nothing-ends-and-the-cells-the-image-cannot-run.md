# k23. Playground: the waits nothing ends, and the Run cells the image cannot run

Difficulty: Medium

Found while k10 ran every Run cell of `doc/en` on the browser image (2026-11-19, 2,051
pages in node; the scripts and how to run them:
`.todo/artefacts/k10-playground-a-java-program-with-threads-freezes-the-browser/`). None
of this freezes a page any more -- the documentation site's cells run in a worker with a
Stop button since k10 -- but each is a cell that says nothing useful.

## What was measured

- **A wait nothing can end still spins or fails by a host name.** The image has one thread
  (`.kb/threads.md`). k10 made a `condition-wait` with no limit refuse in words
  (`Target_RontoConditionVariable`). Left: a Clojure program derefing a promise nothing
  delivered -- `(def p (promise)) (println (deref p 100 :timeout)) (println @p)` did not
  answer in 20 s (which of the two derefs spins was not told apart);
  `(rontolisp:join-thread (rontolisp:current-thread))` answers `ERROR:Unsafe.park`. The
  common point is `LispEvaluator.joinFuture`'s `cf.join()` and whatever else parks: on
  one thread a future that is not done never will be, unless it is a fetch's
  (`BrowserFuture`, which blocks in `Atomics.wait`). A seam in `AsyncRuntime` the image
  substitutes could refuse by name; make sure a fetch's future still waits.
- **Clojure `future`, `pmap`, `pvalues`, `future-call`** fail with `No such class:
  java.util.concurrent.FutureTask`, and `future-done?` and its neighbours with `No matching
  method completedFuture`: 9 reference pages whose cells are Run cells.
- **Java interop by reflection**: about 40 cells of `doc/en/guides/java-interop.md`,
  `doc/en/reference/functions/java-*.md` and `doc/en/clojure/reference/` (`dot.md`,
  `class-member.md`, ..) fail with `No matching ctor found`, `No such class: java.lang.Math`
  and the like, and 12 pages end the whole call with
  `MissingReflectionRegistrationError` / `UnsupportedFeatureError` -- an `Error`, which
  `RontoPlayground.shown` does not catch, so the page shows the JavaScript exception.
- Sockets, files and `ffi:` cells say so in words already (`tcp-listen: TCP sockets are not
  supported in the browser playground`); the Scheme file cells say `FileChannelImpl.open`.

## Leads

1. Decide per group: register what reflection needs in the image (its size is the
   price: measure), or make the fence one that is not a Run cell on those pages
   (`.kb/documentation-site.md`, the fence conventions), or refuse in words.
2. `shown` catching `Error`s of the image by a message of the page's own.
3. Run the pages again after each step: `pages.sh web/dist 90 <pages>`.

## Done when

- No Run cell of `doc/` ends with a host class name or a JavaScript exception: it runs, or
  the page does not offer to run it, or it says in words what the browser has not.
