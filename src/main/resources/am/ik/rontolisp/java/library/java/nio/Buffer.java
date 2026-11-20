// Written for rontolisp's experimental Java subset from the API specification's names
// and behaviour; nothing here is copied or translated from a JDK
// (.kb/java-frontend-provenance.md). Lowered with every program that names it.
package java.nio;

// The four numbers of a buffer, as the specification relates them: mark <= position <=
// limit <= capacity, a mark past a new position or limit dropped. The words of each
// refusal were read off a JDK's answers.
public abstract class Buffer {

    private int mark = -1;

    private int position;

    private int limit;

    private final int capacity;

    Buffer(int mark, int position, int limit, int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: (" + capacity + " < 0)");
        }
        this.capacity = capacity;
        limit(limit);
        position(position);
        if (mark >= 0) {
            this.mark = mark;
        }
    }

    public final int capacity() {
        return capacity;
    }

    public final int position() {
        return position;
    }

    public Buffer position(int newPosition) {
        if (newPosition > limit) {
            throw new IllegalArgumentException("newPosition > limit: (" + newPosition + " > " + limit + ")");
        }
        if (newPosition < 0) {
            throw new IllegalArgumentException("newPosition < 0: (" + newPosition + " < 0)");
        }
        if (mark > newPosition) {
            mark = -1;
        }
        position = newPosition;
        return this;
    }

    public final int limit() {
        return limit;
    }

    public Buffer limit(int newLimit) {
        if (newLimit > capacity) {
            throw new IllegalArgumentException("newLimit > capacity: (" + newLimit + " > " + capacity + ")");
        }
        if (newLimit < 0) {
            throw new IllegalArgumentException("newLimit < 0: (" + newLimit + " < 0)");
        }
        limit = newLimit;
        if (position > newLimit) {
            position = newLimit;
        }
        if (mark > newLimit) {
            mark = -1;
        }
        return this;
    }

    public Buffer mark() {
        mark = position;
        return this;
    }

    public Buffer reset() {
        if (mark < 0) {
            throw new InvalidMarkException();
        }
        position = mark;
        return this;
    }

    public Buffer clear() {
        position = 0;
        limit = capacity;
        mark = -1;
        return this;
    }

    public Buffer flip() {
        limit = position;
        position = 0;
        mark = -1;
        return this;
    }

    public Buffer rewind() {
        position = 0;
        mark = -1;
        return this;
    }

    public final int remaining() {
        int left = limit - position;
        return left > 0 ? left : 0;
    }

    public final boolean hasRemaining() {
        return position < limit;
    }

    public abstract boolean isReadOnly();

    public abstract boolean hasArray();

    public abstract Object array();

    public abstract int arrayOffset();

    public abstract boolean isDirect();

    public abstract Buffer slice();

    public abstract Buffer slice(int index, int length);

    public abstract Buffer duplicate();

    // the mark, -1 for none, for a duplicate
    final int markValue() {
        return mark;
    }

    // the position moved by n, for a relative get or put that checked the room
    final int step(int n) {
        int at = position;
        position = at + n;
        return at;
    }

    // drops the mark, for compact
    final void unmark() {
        mark = -1;
    }
}
