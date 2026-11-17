// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.util;

// The sorts of the numbers are forms over the host's sort; a sort of objects is a merge
// sort written here, stable as the specification asks.
public final class Arrays {

    private Arrays() {
    }

    // an array of the class of another
    static native <T> T[] newArray(T[] like, int length);

    private static void range(int length, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException("fromIndex(" + from + ") > toIndex(" + to + ")");
        }
        if (from < 0) {
            throw new ArrayIndexOutOfBoundsException("Array index out of range: " + from);
        }
        if (to > length) {
            throw new ArrayIndexOutOfBoundsException("Array index out of range: " + to);
        }
    }

    @SafeVarargs
    public static <T> List<T> asList(T... a) {
        return new ArrayView<>(a);
    }

    private static native void sortRange(int[] a, int from, int to);

    public static void sort(int[] a) {
        sortRange(a, 0, a.length);
    }

    public static void sort(int[] a, int from, int to) {
        range(a.length, from, to);
        sortRange(a, from, to);
    }

    private static native void sortRange(long[] a, int from, int to);

    public static void sort(long[] a) {
        sortRange(a, 0, a.length);
    }

    public static void sort(long[] a, int from, int to) {
        range(a.length, from, to);
        sortRange(a, from, to);
    }

    private static native void sortRange(double[] a, int from, int to);

    public static void sort(double[] a) {
        sortRange(a, 0, a.length);
    }

    public static void sort(double[] a, int from, int to) {
        range(a.length, from, to);
        sortRange(a, from, to);
    }

    private static native void sortRange(char[] a, int from, int to);

    public static void sort(char[] a) {
        sortRange(a, 0, a.length);
    }

    public static void sort(char[] a, int from, int to) {
        range(a.length, from, to);
        sortRange(a, from, to);
    }

    public static void sort(Object[] a) {
        sortObjects(a, 0, a.length, null);
    }

    public static void sort(Object[] a, int from, int to) {
        range(a.length, from, to);
        sortObjects(a, from, to, null);
    }

    public static <T> void sort(T[] a, Comparator<? super T> c) {
        sortObjects(a, 0, a.length, c);
    }

    public static <T> void sort(T[] a, int from, int to, Comparator<? super T> c) {
        range(a.length, from, to);
        sortObjects(a, from, to, c);
    }

    // A stable merge sort of a range: by the comparator, or by compareTo when it is null.
    static void sortObjects(Object[] a, int from, int to, Comparator c) {
        if (to - from < 2) {
            return;
        }
        Object[] spare = new Object[to - from];
        merge(a, spare, from, to, c);
    }

    private static int order(Object x, Object y, Comparator c) {
        return c == null ? ((Comparable) x).compareTo(y) : c.compare(x, y);
    }

    private static void merge(Object[] a, Object[] spare, int from, int to, Comparator c) {
        int n = to - from;
        if (n < 8) {
            for (int i = from + 1; i < to; i++) {
                Object moved = a[i];
                int j = i - 1;
                while (j >= from && order(a[j], moved, c) > 0) {
                    a[j + 1] = a[j];
                    j--;
                }
                a[j + 1] = moved;
            }
            return;
        }
        int middle = from + (n >> 1);
        merge(a, spare, from, middle, c);
        merge(a, spare, middle, to, c);
        if (order(a[middle - 1], a[middle], c) <= 0) {
            return;
        }
        System.arraycopy(a, from, spare, 0, middle - from);
        int left = 0;
        int leftEnd = middle - from;
        int right = middle;
        int out = from;
        while (left < leftEnd && right < to) {
            if (order(spare[left], a[right], c) <= 0) {
                a[out++] = spare[left++];
            }
            else {
                a[out++] = a[right++];
            }
        }
        while (left < leftEnd) {
            a[out++] = spare[left++];
        }
    }

    public static <T> T[] copyOf(T[] original, int length) {
        T[] copy = newArray(original, length);
        System.arraycopy(original, 0, copy, 0, original.length < length ? original.length : length);
        return copy;
    }

    public static <T> T[] copyOfRange(T[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        T[] copy = newArray(original, to - from);
        int end = original.length < to ? original.length : to;
        System.arraycopy(original, from, copy, 0, end - from);
        return copy;
    }

    public static String toString(Object[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static void fill(Object[] a, Object value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static boolean equals(Object[] a, Object[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (!(a[i] == null ? b[i] == null : a[i].equals(b[i]))) {
                return false;
            }
        }
        return true;
    }

    public static int hashCode(Object[] a) {
        if (a == null) {
            return 0;
        }
        int hash = 1;
        for (int i = 0; i < a.length; i++) {
            hash = 31 * hash + (a[i] == null ? 0 : a[i].hashCode());
        }
        return hash;
    }

    public static int binarySearch(Object[] a, Object key) {
        int low = 0;
        int high = a.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = ((Comparable) a[middle]).compareTo(key);
            if (order < 0) {
                low = middle + 1;
            }
            else if (order > 0) {
                high = middle - 1;
            }
            else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public static int[] copyOf(int[] original, int length) {
        int[] copy = new int[length];
        System.arraycopy(original, 0, copy, 0, original.length < length ? original.length : length);
        return copy;
    }

    public static int[] copyOfRange(int[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        int[] copy = new int[to - from];
        int end = original.length < to ? original.length : to;
        System.arraycopy(original, from, copy, 0, end - from);
        return copy;
    }

    public static String toString(int[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static void fill(int[] a, int value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(int[] a, int from, int to, int value) {
        range(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = value;
        }
    }

    public static boolean equals(int[] a, int[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static int binarySearch(int[] a, int key) {
        int low = 0;
        int high = a.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = a[middle] < key ? -1 : a[middle] > key ? 1 : 0;
            if (order < 0) {
                low = middle + 1;
            }
            else if (order > 0) {
                high = middle - 1;
            }
            else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public static long[] copyOf(long[] original, int length) {
        long[] copy = new long[length];
        System.arraycopy(original, 0, copy, 0, original.length < length ? original.length : length);
        return copy;
    }

    public static long[] copyOfRange(long[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        long[] copy = new long[to - from];
        int end = original.length < to ? original.length : to;
        System.arraycopy(original, from, copy, 0, end - from);
        return copy;
    }

    public static String toString(long[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static void fill(long[] a, long value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(long[] a, int from, int to, long value) {
        range(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = value;
        }
    }

    public static boolean equals(long[] a, long[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static int binarySearch(long[] a, long key) {
        int low = 0;
        int high = a.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = a[middle] < key ? -1 : a[middle] > key ? 1 : 0;
            if (order < 0) {
                low = middle + 1;
            }
            else if (order > 0) {
                high = middle - 1;
            }
            else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public static double[] copyOf(double[] original, int length) {
        double[] copy = new double[length];
        System.arraycopy(original, 0, copy, 0, original.length < length ? original.length : length);
        return copy;
    }

    public static double[] copyOfRange(double[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        double[] copy = new double[to - from];
        int end = original.length < to ? original.length : to;
        System.arraycopy(original, from, copy, 0, end - from);
        return copy;
    }

    public static String toString(double[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static void fill(double[] a, double value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(double[] a, int from, int to, double value) {
        range(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = value;
        }
    }

    public static boolean equals(double[] a, double[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (Double.compare(a[i], b[i]) != 0) {
                return false;
            }
        }
        return true;
    }

    public static int binarySearch(double[] a, double key) {
        int low = 0;
        int high = a.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = Double.compare(a[middle], key);
            if (order < 0) {
                low = middle + 1;
            }
            else if (order > 0) {
                high = middle - 1;
            }
            else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public static char[] copyOf(char[] original, int length) {
        char[] copy = new char[length];
        System.arraycopy(original, 0, copy, 0, original.length < length ? original.length : length);
        return copy;
    }

    public static char[] copyOfRange(char[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        char[] copy = new char[to - from];
        int end = original.length < to ? original.length : to;
        System.arraycopy(original, from, copy, 0, end - from);
        return copy;
    }

    public static String toString(char[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static void fill(char[] a, char value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(char[] a, int from, int to, char value) {
        range(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = value;
        }
    }

    public static boolean equals(char[] a, char[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static int binarySearch(char[] a, char key) {
        int low = 0;
        int high = a.length - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            int order = a[middle] < key ? -1 : a[middle] > key ? 1 : 0;
            if (order < 0) {
                low = middle + 1;
            }
            else if (order > 0) {
                high = middle - 1;
            }
            else {
                return middle;
            }
        }
        return -(low + 1);
    }

    public static boolean[] copyOf(boolean[] original, int length) {
        boolean[] copy = new boolean[length];
        System.arraycopy(original, 0, copy, 0, original.length < length ? original.length : length);
        return copy;
    }

    public static boolean[] copyOfRange(boolean[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        boolean[] copy = new boolean[to - from];
        int end = original.length < to ? original.length : to;
        System.arraycopy(original, from, copy, 0, end - from);
        return copy;
    }

    public static String toString(boolean[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static void fill(boolean[] a, boolean value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(boolean[] a, int from, int to, boolean value) {
        range(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = value;
        }
    }

    public static boolean equals(boolean[] a, boolean[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static int hashCode(int[] a) {
        if (a == null) {
            return 0;
        }
        int hash = 1;
        for (int i = 0; i < a.length; i++) {
            hash = 31 * hash + a[i];
        }
        return hash;
    }

    public static byte[] copyOf(byte[] original, int length) {
        byte[] copy = new byte[length];
        System.arraycopy(original, 0, copy, 0, original.length < length ? original.length : length);
        return copy;
    }

    public static byte[] copyOfRange(byte[] original, int from, int to) {
        if (from > to) {
            throw new IllegalArgumentException(from + " > " + to);
        }
        byte[] copy = new byte[to - from];
        int end = original.length < to ? original.length : to;
        System.arraycopy(original, from, copy, 0, end - from);
        return copy;
    }

    public static String toString(short[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static String toString(float[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static String deepToString(Object[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        deepAppend(a, null, text);
        return text.toString();
    }

    // OPEN is the arrays being written around A, each {array, the one around it}: an
    // array met again inside itself is written [...]
    private static void deepAppend(Object[] a, Object[] open, StringBuilder text) {
        Object[] inside = {a, open};
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            Object e = a[i];
            if (e instanceof Object[] nested) {
                boolean again = false;
                for (Object[] o = inside; o != null && !again; o = (Object[]) o[1]) {
                    again = o[0] == nested;
                }
                if (again) {
                    text.append("[...]");
                } else {
                    deepAppend(nested, inside, text);
                }
            } else if (e instanceof int[] v) {
                text.append(toString(v));
            } else if (e instanceof long[] v) {
                text.append(toString(v));
            } else if (e instanceof short[] v) {
                text.append(toString(v));
            } else if (e instanceof char[] v) {
                text.append(toString(v));
            } else if (e instanceof byte[] v) {
                text.append(toString(v));
            } else if (e instanceof boolean[] v) {
                text.append(toString(v));
            } else if (e instanceof float[] v) {
                text.append(toString(v));
            } else if (e instanceof double[] v) {
                text.append(toString(v));
            } else {
                text.append(e);
            }
        }
        text.append(']');
    }

    public static String toString(byte[] a) {
        if (a == null) {
            return "null";
        }
        StringBuilder text = new StringBuilder();
        text.append('[');
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                text.append(", ");
            }
            text.append(a[i]);
        }
        return text.append(']').toString();
    }

    public static void fill(byte[] a, byte value) {
        for (int i = 0; i < a.length; i++) {
            a[i] = value;
        }
    }

    public static void fill(byte[] a, int from, int to, byte value) {
        range(a.length, from, to);
        for (int i = from; i < to; i++) {
            a[i] = value;
        }
    }

    public static boolean equals(byte[] a, byte[] b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null || a.length != b.length) {
            return false;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    public static int hashCode(byte[] a) {
        if (a == null) {
            return 0;
        }
        int hash = 1;
        for (int i = 0; i < a.length; i++) {
            hash = 31 * hash + a[i];
        }
        return hash;
    }

    // The list Arrays.asList answers: the array itself, so of a fixed size.
    static final class ArrayView<E> extends AbstractList<E> {

        private final E[] held;

        ArrayView(E[] held) {
            if (held == null) {
                throw new NullPointerException();
            }
            this.held = held;
        }

        public int size() {
            return held.length;
        }

        public E get(int index) {
            return held[index];
        }

        public E set(int index, E element) {
            E old = held[index];
            held[index] = element;
            return old;
        }

        public Object[] toArray() {
            Object[] out = new Object[held.length];
            System.arraycopy(held, 0, out, 0, held.length);
            return out;
        }

        public void sort(Comparator<? super E> c) {
            sortObjects(held, 0, held.length, c);
        }
    }
}
