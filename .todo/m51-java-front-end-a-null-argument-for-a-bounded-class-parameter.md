# m51. Java front end: a null argument for a `Class<V>` parameter of a bounded `V`, wanted as `Object`

Difficulty: Medium

Found in m19. javac infers `V` from its bound where the only argument naming it is `null`
and the value is wanted as `Object`; the subset refuses the call at compile time.

```java
public class Inf {
    static <V extends Number> V f(Class<V> c) { return null; }
    public static void main(String[] args) {
        Object a = f(null);
        System.out.println(a);   // a JDK prints null
    }
}
```

`error: Inf.java:4:20: incompatible types: inferred type does not conform to upper bound(s)`
(`JavaLowering`, the bounds check after `inference.solution`). `println(f(null))` and
`Object a = f(Integer.class)` compile. Met as a lambda returning
`Files.getFileAttributeView(path, null)`. JLS 18.5.2 for the target type's bound and 18.4
for resolving a variable whose only proper bounds are `Object` and its declared one. Start
from a failing java-spec case.
