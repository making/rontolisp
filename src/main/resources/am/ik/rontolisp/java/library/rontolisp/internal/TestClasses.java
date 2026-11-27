// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.ClassDescriptor;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

// The @Nested classes of a class as a ClassOrderer that is none of JUnit's three by
// order, name and display name is handed them -- JUnit's random one, or one of the
// program's --, and their run in the order it leaves them in. A class it drops from the
// list still runs, after the others; one it adds does not. No run names a configuration
// parameter, so the context has none. A class's annotations are reflected where the
// orderer asks for them.
public final class TestClasses implements ClassOrdererContext {

    // A @Nested class: the class, its display name and what runs its tests.
    public static final class Nested implements ClassDescriptor {

        private final Class<?> testClass;

        private final String displayName;

        final Runnable body;

        public Nested(Class<?> testClass, String displayName, Runnable body) {
            this.testClass = testClass;
            this.displayName = displayName;
            this.body = body;
        }

        @Override
        public Class<?> getTestClass() {
            return testClass;
        }

        @Override
        public String getDisplayName() {
            return displayName;
        }

        @Override
        public boolean isAnnotated(Class<? extends Annotation> annotationType) {
            return testClass.isAnnotationPresent(annotationType);
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> annotationType) {
            return Optional.ofNullable(testClass.getAnnotation(annotationType));
        }

        @Override
        public <A extends Annotation> List<A> findRepeatableAnnotations(Class<A> annotationType) {
            return List.of(testClass.getAnnotationsByType(annotationType));
        }
    }

    private final List<Nested> classes;

    private TestClasses(List<Nested> classes) {
        this.classes = classes;
    }

    // Runs the @Nested classes of a class in the order the orderer puts them in.
    public static void ordered(ClassOrderer orderer, List<Nested> classes) {
        List<Nested> ordered = new ArrayList<>(classes);
        orderer.orderClasses(new TestClasses(ordered));
        List<Nested> left = new ArrayList<>(classes);
        for (Nested each : ordered) {
            if (left.remove(each)) {
                each.body.run();
            }
        }
        for (Nested each : left) {
            each.body.run();
        }
    }

    @Override
    public List<? extends ClassDescriptor> getClassDescriptors() {
        return classes;
    }

    @Override
    public Optional<String> getConfigurationParameter(String key) {
        return Optional.empty();
    }
}
