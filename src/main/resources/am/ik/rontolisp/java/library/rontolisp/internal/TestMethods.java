// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.MethodDescriptor;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.MethodOrdererContext;

// The tests of a class as a MethodOrderer that is none of JUnit's three by order, name
// and display name is handed them -- JUnit's random one, or one of the program's --, and
// their run in the order it leaves them in. A test it drops from the list still runs,
// after the others, as under JUnit (measured); one it adds does not. No run names a
// configuration parameter, so the context has none. A test's method and its annotations
// are reflected where the orderer asks for them.
public final class TestMethods implements MethodOrdererContext {

    // A test of the class: its method, its display name and what runs it.
    public static final class Test implements MethodDescriptor {

        private final Class<?> declaringClass;

        private final String methodName;

        private final Class<?>[] parameterTypes;

        private final String displayName;

        final Runnable body;

        public Test(Class<?> declaringClass, String methodName, Class<?>[] parameterTypes, String displayName,
                Runnable body) {
            this.declaringClass = declaringClass;
            this.methodName = methodName;
            this.parameterTypes = parameterTypes;
            this.displayName = displayName;
            this.body = body;
        }

        @Override
        public java.lang.reflect.Method getMethod() {
            try {
                return declaringClass.getDeclaredMethod(methodName, parameterTypes);
            }
            catch (NoSuchMethodException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public boolean isAnnotated(Class<? extends Annotation> annotationType) {
            return getMethod().isAnnotationPresent(annotationType);
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
            return Optional.ofNullable(getMethod().getAnnotation(annotationType));
        }

        @Override
        public <A extends Annotation> List<A> findRepeatableAnnotations(Class<A> annotationType) {
            return List.of(getMethod().getAnnotationsByType(annotationType));
        }
    }

    private final Class<?> testClass;

    private final List<Test> tests;

    private TestMethods(Class<?> testClass, List<Test> tests) {
        this.testClass = testClass;
        this.tests = tests;
    }

    // Runs the tests of a class in the order the orderer puts them in.
    public static void ordered(MethodOrderer orderer, Class<?> testClass, List<Test> tests) {
        List<Test> ordered = new ArrayList<>(tests);
        orderer.orderMethods(new TestMethods(testClass, ordered));
        List<Test> left = new ArrayList<>(tests);
        for (Test each : ordered) {
            if (left.remove(each)) {
                each.body.run();
            }
        }
        for (Test each : left) {
            each.body.run();
        }
    }

    @Override
    public Class<?> getTestClass() {
        return testClass;
    }

    @Override
    public List<? extends MethodDescriptor> getMethodDescriptors() {
        return tests;
    }

    @Override
    public Optional<String> getConfigurationParameter(String key) {
        return Optional.empty();
    }
}
