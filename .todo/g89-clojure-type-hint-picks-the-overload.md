# Clojure interop: a `^Callable` / `^Runnable` hint does not pick the overload

Difficulty: Medium

```clojure
(import (java.util.concurrent Executors))
(let [e (Executors/newFixedThreadPool 1)]
  (println @(.submit e ^Callable (fn [] 42)) @(.submit e ^Runnable (fn [] 42)))
  (.shutdown e))
;; clj: 42 nil   cljw 1.15.4: 42 42   rontolisp: nil nil
```

The hint on the argument is ignored and `submit(Runnable)` is chosen, so the future yields
nil. Every `ExecutorService` program written the usual way gets nil. Honor an argument's
`:tag` in overload resolution on the interpreter and JVM (`.kb/clojure-frontend.md` "Java
interop", "Host interfaces"); without a hint, prefer what clj's reflector picks.
