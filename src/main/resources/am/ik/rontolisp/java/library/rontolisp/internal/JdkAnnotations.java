// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package rontolisp.internal;

import java.lang.annotation.Annotation;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Arrays;

// The annotations a class of the JDK and its members carry, as the lowering's table of the
// JDK's members has them (read off a running JDK): an instance of the annotation type where
// the library has the type, else one answering its type alone, each with the text a JDK
// writes of it. Equal and hashed as java.lang.annotation.Annotation specifies.
public final class JdkAnnotations {

    private JdkAnnotations() {
    }

    static Annotation deprecated(Object type, String since, boolean forRemoval, String text) {
        return new Deprecation((Class<?>) type, since, forRemoval, text);
    }

    static Annotation retention(Object type, String value, String text) {
        return new Retained((Class<?>) type, RetentionPolicy.valueOf(value), text);
    }

    static Annotation target(Object type, String values, String text) {
        String[] names = values.isEmpty() ? new String[0] : values.split(",");
        ElementType[] kinds = new ElementType[names.length];
        for (int index = 0; index < names.length; index++) {
            kinds[index] = ElementType.valueOf(names[index]);
        }
        return new Targeted((Class<?>) type, kinds, text);
    }

    static Annotation functional(Object type, String text) {
        return new Functional((Class<?>) type, text);
    }

    static Annotation safeVarargs(Object type, String text) {
        return new Safe((Class<?>) type, text);
    }

    static Annotation documented(Object type, String text) {
        return new Document((Class<?>) type, text);
    }

    static Annotation inherited(Object type, String text) {
        return new Inheriting((Class<?>) type, text);
    }

    // one of a type the library does not have, of no element
    static Annotation marker(Object type, String text) {
        return new Marker((Class<?>) type, text);
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Annotation> annotation(Class<?> type) {
        return (Class<? extends Annotation>) type;
    }

    private static final class Deprecation implements Deprecated {

        private final Class<?> type;

        private final String since;

        private final boolean forRemoval;

        private final String text;

        Deprecation(Class<?> type, String since, boolean forRemoval, String text) {
            this.type = type;
            this.since = since;
            this.forRemoval = forRemoval;
            this.text = text;
        }

        public String since() {
            return since;
        }

        public boolean forRemoval() {
            return forRemoval;
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof Deprecated that && since.equals(that.since()) && forRemoval == that.forRemoval();
        }

        public int hashCode() {
            return ((127 * "since".hashCode()) ^ since.hashCode())
                    + ((127 * "forRemoval".hashCode()) ^ (forRemoval ? 1231 : 1237));
        }

        public String toString() {
            return text;
        }
    }

    private static final class Retained implements Retention {

        private final Class<?> type;

        private final RetentionPolicy value;

        private final String text;

        Retained(Class<?> type, RetentionPolicy value, String text) {
            this.type = type;
            this.value = value;
            this.text = text;
        }

        public RetentionPolicy value() {
            return value;
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof Retention that && value == that.value();
        }

        public int hashCode() {
            return (127 * "value".hashCode()) ^ value.hashCode();
        }

        public String toString() {
            return text;
        }
    }

    private static final class Targeted implements Target {

        private final Class<?> type;

        private final ElementType[] value;

        private final String text;

        Targeted(Class<?> type, ElementType[] value, String text) {
            this.type = type;
            this.value = value;
            this.text = text;
        }

        public ElementType[] value() {
            return value.clone();
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof Target that && Arrays.equals(value, that.value());
        }

        public int hashCode() {
            return (127 * "value".hashCode()) ^ Arrays.hashCode(value);
        }

        public String toString() {
            return text;
        }
    }

    private static final class Functional implements FunctionalInterface {

        private final Class<?> type;

        private final String text;

        Functional(Class<?> type, String text) {
            this.type = type;
            this.text = text;
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof FunctionalInterface;
        }

        public int hashCode() {
            return 0;
        }

        public String toString() {
            return text;
        }
    }

    private static final class Safe implements SafeVarargs {

        private final Class<?> type;

        private final String text;

        Safe(Class<?> type, String text) {
            this.type = type;
            this.text = text;
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof SafeVarargs;
        }

        public int hashCode() {
            return 0;
        }

        public String toString() {
            return text;
        }
    }

    private static final class Document implements Documented {

        private final Class<?> type;

        private final String text;

        Document(Class<?> type, String text) {
            this.type = type;
            this.text = text;
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof Documented;
        }

        public int hashCode() {
            return 0;
        }

        public String toString() {
            return text;
        }
    }

    private static final class Inheriting implements Inherited {

        private final Class<?> type;

        private final String text;

        Inheriting(Class<?> type, String text) {
            this.type = type;
            this.text = text;
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof Inherited;
        }

        public int hashCode() {
            return 0;
        }

        public String toString() {
            return text;
        }
    }

    private static final class Marker implements Annotation {

        private final Class<?> type;

        private final String text;

        Marker(Class<?> type, String text) {
            this.type = type;
            this.text = text;
        }

        public Class<? extends Annotation> annotationType() {
            return annotation(type);
        }

        public boolean equals(Object other) {
            return other instanceof Annotation that && that.annotationType() == type;
        }

        public int hashCode() {
            return 0;
        }

        public String toString() {
            return text;
        }
    }
}
