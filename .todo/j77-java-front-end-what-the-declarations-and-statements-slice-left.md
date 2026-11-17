# j77. Java front end: what the declarations and statements slice (j69) left

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** No JDK jar, source, Javadoc text or test is copied or translated; what ships is
written here from the specification; no surface says "Java compatible".

After j69 (`.kb/java-frontend.md`, "Declarations and statements past the first slice" and
"Static initialization"). Each line is refused by name or stated in
`doc/*/java/deviations.md` today.

- **Errors a compiler owes that are reported only where sure**: definite assignment through
  `switch`, `try`, `do`, `&&`/`||` and `?:` (an assignment on any path counts today); a blank
  `final` assigned twice; a blank local assigned twice and then read by a lambda or a local
  class; an unreachable statement and a missing `return` past the cases `exits`/`completes`
  decide (a `do` loop, a labeled statement, a `switch` without a default). The rule to keep:
  refuse only what a compiler refuses.
- **Refused by name**: `Interface.super.m()`; a class extending an inner class from outside
  that class's outer class (`outer.super(..)`); an anonymous class made with `outer.new`; a
  switch expression whose colon cases run into the next; `Outer.this` ahead of `super(..)`.
- **To measure, not assume** (four backends, `.todo/artefacts/j68-java-front-end-spike/bench.sh`
  for the method): a pattern's binding as a variable set in the test against a `let` in the
  taken branch; a dense `int` switch as a `cond` of `eql` against a `case`; what a lazy class's
  test costs a static call in a loop, and whether the classes `JavaInitOrder` leaves lazy in
  real programs are the ones that have to be.
- An anonymous class declared inside a lambda is numbered in the order written; a compiler
  attributes some lambda bodies later. Compare `getClass().getName()` on a program that has
  both.
