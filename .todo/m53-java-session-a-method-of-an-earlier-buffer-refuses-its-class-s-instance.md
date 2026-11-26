# m53. Java session: a `Method` of an earlier buffer refuses its class's instance

Difficulty: Low

In the Java REPL (`--source-language java`), a `Method` of a class of the JDK found in one
buffer and invoked in a later one refuses an instance of its class; invoked in the buffer that
found it, it runs. Same before and after m18 (2026-11-25):

```
String n = "length";
var m = String.class.getDeclaredMethod(n);
System.out.println(m.invoke("abcd"));
```

prints `Error: Exception java.lang.IllegalArgumentException: object of type java.lang.String is
not an instance of java.lang.String` at the third buffer, where a JDK prints `4`;
`System.out.println(String.class.getDeclaredMethod(n).invoke("abcd"))` in one buffer prints `4`.

The test is `%jdk-tests` of the class's number (`Session.jdkNumbers` keeps the numbers across
buffers). Find which buffer's functions the later call reaches, fix it, and pin it with a
session test (as `JavaZoneRulesTest`'s session reads forms; this needs a run).
