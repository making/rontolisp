# j86. Java front end: what the inner types, intersections and inference slice (j78) left

Difficulty: Medium

**Read `.kb/java-frontend-provenance.md` before writing anything** (licence and naming).

After j78 (`.kb/java-frontend.md`, "Type parameters", "Inference", "Lambdas", "j78"
measurements). Each line is a program a compiler takes that this refuses, or the reverse.

- **The unchecked warnings.** The JDK 25 source launcher prints them on standard error
  (`warning: [unchecked] unchecked call to add(E) as a member of the raw type List`,
  `unchecked conversion`, `unchecked cast`, with the caret line, the `where` clause and
  `N warnings`), honouring `@SuppressWarnings("unchecked")`, which the parser drops today. The
  front end has no channel for a warning: one from `java` to the CLI is the first step.
- **A call that is an argument of an overloaded method** (several candidates of the arity)
  and fails without a target keeps its error (`deviations.md`): javac tries each candidate's
  parameter type. Lead: lower the argument once per candidate of the arity against that
  parameter, state put back between (`Standing`), only on the failure path.
- **A lambda or a method reference cast to an intersection** (`(Runnable & Marker) () -> ..`)
  is refused by name: the lambda would need to be tagged for each marker interface.
- **`Type::<T>new`** is refused by name.
- **To find, not assume**: j70 noted that what an open variable is read as is not checked
  against the other uses of the same variable (accepted where a compiler refuses). No program
  showing it was found in j78 (`cast(o) + 1` is refused as javac refuses it). Find one or drop
  the line.
