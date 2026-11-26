// Written for rontolisp's experimental Java subset; nothing here is copied or translated
// from a JDK or from a test library (.kb/java-frontend-provenance.md). Lowered with
// every program that names it.
package rontolisp.internal;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.TestInfo;

// What a test, or what runs around tests, is given as its TestInfo: the display name of
// the test or of its class, the class, the method, and the tags. The tags are the ones
// written on the method, then the class's, each trimmed, the first of equal ones kept;
// one that is no valid tag (blank, or holding whitespace, an ISO control character or
// one of , ( ) & | !, as @Tag's documentation states) is left out, as JUnit leaves it.
public final class TestInformation implements TestInfo {

    private final String displayName;

    private final String[] tags;

    private final Class<?> testClass;

    private final Class<?> declaringClass;

    private final String methodName;

    private final Class<?>[] parameterTypes;

    // The information of a test, whose method a class declares, maybe a class above the
    // test's class or an interface; declaringClass, methodName and parameterTypes are null
    // for a class's.
    public TestInformation(String displayName, String[] tags, Class<?> testClass, Class<?> declaringClass,
            String methodName, Class<?>[] parameterTypes) {
        this.displayName = displayName;
        this.tags = tags;
        this.testClass = testClass;
        this.declaringClass = declaringClass;
        this.methodName = methodName;
        this.parameterTypes = parameterTypes;
    }

    private static boolean isTag(String tag) {
        if (tag.isEmpty()) {
            return false;
        }
        for (int i = 0; i < tag.length(); i++) {
            char c = tag.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c) || ",()&|!".indexOf(c) >= 0) {
                return false;
            }
        }
        return true;
    }

    @Override
    public String getDisplayName() {
        return displayName;
    }

    // Made where asked, so that a program asking for none holds no set.
    @Override
    public Set<String> getTags() {
        Set<String> valid = new LinkedHashSet<>();
        for (String tag : tags) {
            String trimmed = tag.strip();
            if (isTag(trimmed)) {
                valid.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(valid);
    }

    @Override
    public Optional<Class<?>> getTestClass() {
        return Optional.of(testClass);
    }

    @Override
    public Optional<Method> getTestMethod() {
        if (methodName == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(declaringClass.getDeclaredMethod(methodName, parameterTypes));
        }
        catch (NoSuchMethodException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
