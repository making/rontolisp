// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

import java.util.function.Supplier;

public final class Objects {

    private Objects() {
    }

    public static boolean equals(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    public static int hashCode(Object o) {
        return o == null ? 0 : o.hashCode();
    }

    public static int hash(Object... values) {
        return Arrays.hashCode(values);
    }

    public static String toString(Object o) {
        return String.valueOf(o);
    }

    public static String toString(Object o, String whenNull) {
        return o == null ? whenNull : o.toString();
    }

    public static boolean isNull(Object o) {
        return o == null;
    }

    public static boolean nonNull(Object o) {
        return o != null;
    }

    public static <T> T requireNonNull(T o) {
        if (o == null) {
            throw new NullPointerException();
        }
        return o;
    }

    public static <T> T requireNonNull(T o, String message) {
        if (o == null) {
            throw new NullPointerException(message);
        }
        return o;
    }

    public static <T> T requireNonNull(T o, Supplier<String> messageSupplier) {
        if (o == null) {
            throw new NullPointerException(messageSupplier == null ? null : messageSupplier.get());
        }
        return o;
    }

    public static <T> T requireNonNullElseGet(T o, Supplier<? extends T> supplier) {
        return o != null ? o
                : requireNonNull(requireNonNull(supplier, "supplier").get(), "supplier.get()");
    }

    public static <T> T requireNonNullElse(T o, T other) {
        return o != null ? o : requireNonNull(other, "defaultObj");
    }

    public static <T> int compare(T a, T b, Comparator<? super T> c) {
        return a == b ? 0 : c.compare(a, b);
    }

    public static int checkFromIndexSize(int fromIndex, int size, int length) {
        if (fromIndex < 0 || size < 0 || fromIndex > length - size || length < 0) {
            throw new IndexOutOfBoundsException(
                    "Range [" + fromIndex + ", " + fromIndex + " + " + size + ") out of bounds for length " + length);
        }
        return fromIndex;
    }

    public static int checkFromToIndex(int fromIndex, int toIndex, int length) {
        if (fromIndex < 0 || fromIndex > toIndex || toIndex > length) {
            throw new IndexOutOfBoundsException(
                    "Range [" + fromIndex + ", " + toIndex + ") out of bounds for length " + length);
        }
        return fromIndex;
    }

    public static int checkIndex(int index, int length) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
        }
        return index;
    }
}
