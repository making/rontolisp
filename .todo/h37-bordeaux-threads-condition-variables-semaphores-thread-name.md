# h37. bordeaux-threads shim: condition variables, semaphores, `thread-name`, a distinct lock type

Difficulty: Medium

Found 2026-10-30 probing `lparallel` and `log4cl`. The shim
(`src/main/resources/am/ik/rontolisp/eval/bordeaux-threads.lisp`) has only the locking
subset plus bt2 thread creation.

- lparallel: `bt:condition-wait` (then `condition-notify`, `make-condition-variable`).
- log4cl: `bt:thread-name`, `bt:make-semaphore` (+ `signal-semaphore`/`wait-on-semaphore`).
  Also needs `makunbound` (h32).
- serapeum: `(class-of (bt:make-recursive-lock))` answers INTEGER because a lock IS a mutex
  integer handle, so a method on it specializes on all integers (h33 item 2). Give locks a
  type of their own.

Fill the v1 API to the bordeaux-threads 0.9 export list (`all-threads`, `thread-yield`,
`with-recursive-lock-held`, `lock-p`, `*default-special-bindings*` ...). Each member is
real where the backend has threads (interpreter, JVM). On wasm it signals at call time,
the shim's existing rule. Condition variables need a runtime primitive next to
`rontolisp:*-mutex` (`.kb/mutexes.md`).
