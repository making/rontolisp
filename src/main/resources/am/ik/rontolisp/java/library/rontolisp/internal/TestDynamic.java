// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.util.Iterator;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicContainer;
import org.junit.jupiter.api.DynamicNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.platform.commons.JUnitException;
import org.opentest4j.TestAbortedException;

// What a @TestFactory method makes, run as JUnit Jupiter ran it under a JDK: its nodes
// one after the other as they are made, a test counted and run, a container's nodes
// inside it, each named by its place among its container's ([2][1]) and reported with its
// display name. What the factory or a container throws while its nodes are made is an
// error of its own, the tests made before it having run; an assumption that does not
// hold there counts nothing, as surefire counts it. A stream is closed once its nodes
// ran. A method per type a factory may be declared to return, so that a factory of a
// list reaches no stream.
public final class TestDynamic {

    private TestDynamic() {
    }

    // Runs a factory, whose body makes its instance, calls what runs around each test
    // and hands what the factory returns to one of the methods below.
    public static void factory(TestRun run, String name, String displayName, TestRun.Body body) {
        try {
            body.run();
        }
        catch (TestAbortedException aborted) {
            // no test is counted
        }
        catch (Throwable thrown) {
            run.broken(name, displayName, thrown);
        }
    }

    public static void run(TestRun run, String name, DynamicNode node) throws Throwable {
        one(run, name + "[1]", made(node));
    }

    public static void run(TestRun run, String name, DynamicNode[] nodes) throws Throwable {
        made(nodes);
        for (int i = 0; i < nodes.length; i++) {
            one(run, name + "[" + (i + 1) + "]", nodes[i]);
        }
    }

    public static void run(TestRun run, String name, Iterable<? extends DynamicNode> nodes) throws Throwable {
        each(run, name, made(nodes).iterator());
    }

    public static void run(TestRun run, String name, Iterator<? extends DynamicNode> nodes) throws Throwable {
        each(run, name, made(nodes));
    }

    public static void run(TestRun run, String name, Stream<? extends DynamicNode> nodes) throws Throwable {
        try (Stream<? extends DynamicNode> closed = made(nodes)) {
            each(run, name, closed.iterator());
        }
    }

    private static <T> T made(T returned) {
        if (returned == null) {
            throw new JUnitException("@TestFactory method must not return null");
        }
        return returned;
    }

    private static void each(TestRun run, String name, Iterator<? extends DynamicNode> nodes) throws Throwable {
        int place = 0;
        while (nodes.hasNext()) {
            one(run, name + "[" + (++place) + "]", nodes.next());
        }
    }

    private static void one(TestRun run, String name, DynamicNode node) {
        String displayName = node.getDisplayName();
        if (node instanceof DynamicContainer container) {
            factory(run, name, displayName, () -> run(run, name, container.getChildren()));
        }
        else {
            DynamicTest test = (DynamicTest) node;
            run.test(name, displayName, () -> test.getExecutable().execute());
        }
    }
}
