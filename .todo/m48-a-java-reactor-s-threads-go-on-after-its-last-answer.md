# m48. A Java reactor's threads go on after its last answer

Difficulty: Medium

Under `--host-timer` a reactor's threads take their turns while the host has a request in
hand: a call that returns leaving threads ready, or waiting for a time, rings a call idling in
the host, and with none idling they wait for the next request (`.kb/java-frontend.md`, "A
server's waits through its host"). So work after the last answer goes on nowhere: the
servlet corpus's stop after `/app/stop` (`destroy hello`, `context /app destroyed`,
`stopped`: its reactor `logDeviates`), `Server.java`'s `main` after the stop's cut, a
scheduled executor's next run.

- Plan: a turn the host gives the program when its timer comes, as a component's alarm does
  (`%java-thread-alarm`): an entry that runs the threads until none can go on and answers the
  soonest time one waits for (or none), called by the glue through its queue at that time --
  and once after a call that leaves threads ready. A Worker keeps the isolate for it only
  within `ctx.waitUntil`: the glue hands the turn's promise to the request whose call armed it.
- Decide the entry's name and who declares it: an export the glue recognises, as it does the
  envelope's bridge (`ReactorEnvelope`), or a hook in the `__ronto_` namespace the backend
  emits beside `__ronto_set_time`.
- Failing tests first: the servlet program's reactor log equal to the JDK's but for what the
  reactor cannot have; `Server.java`'s `main went on once the server stopped`.
