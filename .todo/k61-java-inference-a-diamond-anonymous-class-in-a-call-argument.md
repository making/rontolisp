# k61. Java front end: a diamond anonymous class in a call's argument is not inferred

Difficulty: Medium

**Important -- licence and naming: read `.kb/java-frontend-provenance.md` before writing
anything.** Behaviour is learned by running a program under a JDK, never from its sources.

Found by k55 (2026-11-20) writing its probe of the reversed views: an anonymous class
created with `<>` is inferred where it is assigned (`List<Integer> a = new ArrayList<>()
{{ add(1); }};` and `new Comparator<>() { .. }` both run), but not as an argument of a call
whose parameter gives its type:

```java
static void list(String label, List<Integer> l) { .. }
list("x", new ArrayList<>() {{ add(2); }});          // javac: fine
list("y", same(new ArrayList<>() {{ add(3); }}));    // javac: fine, through a generic call
```

Both are refused with `method list in class Dia2 cannot be applied to given types; required:
String,List<Integer>; found: String,` -- the argument's type printed as nothing. With the
type written (`new ArrayList<Integer>() {{ .. }}`) the call runs. `.kb/java-frontend.md`,
"Inference": a creation with `<>` finds types of its own (`diamondArguments`,
`diamondParameters`), as a call does; the anonymous class's body is lowered after its
supertype is known, which in an argument position is after the call's inference. The fix
belongs where a lambda's argument waits for the chosen parameter's type: an anonymous class
with `<>` as an argument is a poly expression, the class given its supertype once the
overload is chosen. A `refused:` case is not the pin (javac accepts it): a line of
`a-type-argument-is-found-from-the-arguments-the-target-and-the-lambdas`.
