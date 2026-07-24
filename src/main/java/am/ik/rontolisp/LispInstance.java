package am.ik.rontolisp;

import java.util.Arrays;

/**
 * An instance of a {@code defstruct} type or of a CLOS class (a {@code define-condition}
 * condition included): a {@link LispLayout} plus the slot values, in layout order.
 *
 * <p>
 * An instance is a first-class object rather than a tagged list, so {@code consp} and
 * {@code listp} are nil on it and the printer needs no registry: the layout riding on the
 * value supplies the type name and the slot names, which is what lets
 * {@code #S(POINT :X 1 :Y 2)} and {@code #<PT :X 5>} be produced identically by the
 * interpreter, the JVM backend and the WASM backend. Slots are mutable, because
 * {@code (setf (point-x p) v)} and {@code (setf (slot-value o 'x) v)} write in place.
 *
 * <p>
 * Instances are self-evaluating (CLHS 3.1.2.1.3: an object that is neither a symbol nor a
 * cons evaluates to itself), so one read as a {@code #S(...)} literal survives
 * {@code quote} and backquote with no special casing.
 */
public final class LispInstance implements LispVal {

	private final LispLayout layout;

	private final LispVal[] slots;

	/**
	 * Creates an instance over the given layout. The array is taken by reference: the
	 * caller must not retain it.
	 * @param layout the type layout
	 * @param slots the slot values, in layout order
	 */
	public LispInstance(LispLayout layout, LispVal[] slots) {
		this.layout = layout;
		this.slots = slots;
	}

	/**
	 * Creates an instance with every slot set to nil.
	 * @param layout the type layout
	 * @return the fresh instance
	 */
	public static LispInstance ofNilSlots(LispLayout layout) {
		LispVal[] slots = new LispVal[layout.slotCount()];
		Arrays.fill(slots, LispNil.INSTANCE);
		return new LispInstance(layout, slots);
	}

	/**
	 * The layout describing this instance's type.
	 * @return the layout
	 */
	public LispLayout layout() {
		return this.layout;
	}

	/**
	 * The number of slots.
	 * @return the slot count
	 */
	public int slotCount() {
		return this.slots.length;
	}

	/**
	 * Reads a slot by 0-based index.
	 * @param index the slot index
	 * @return the slot value
	 * @throws IndexOutOfBoundsException when the index is outside the layout
	 */
	public LispVal slot(int index) {
		return this.slots[index];
	}

	/**
	 * Writes a slot by 0-based index.
	 * @param index the slot index
	 * @param value the new value
	 * @throws IndexOutOfBoundsException when the index is outside the layout
	 */
	public void setSlot(int index, LispVal value) {
		this.slots[index] = value;
	}

	/**
	 * A shallow copy sharing the layout (the {@code copy-<name>} copier).
	 * @return a fresh instance with the same layout and slot values
	 */
	public LispInstance shallowCopy() {
		return new LispInstance(this.layout, this.slots.clone());
	}

	/**
	 * Whether this instance's type is the given tag.
	 * @param tag the instance tag ({@code %struct-<name>} / {@code %class-<name>})
	 * @return true when the layout carries that tag
	 */
	public boolean hasTag(String tag) {
		return this.layout.tag().equals(tag);
	}

	@Override
	public String print() {
		return render(true);
	}

	@Override
	public String display() {
		return render(false);
	}

	/**
	 * Renders the instance in {@code #S(NAME :SLOT value ...)} or
	 * {@code #<NAME :SLOT value ...>} syntax. The {@code #S}/{@code #<} frame and the
	 * colon on each slot key are literal syntax and so are emitted under {@code princ}
	 * too (CLHS 22.1.3.12); only the slot VALUES follow the ambient escape mode.
	 * @param escape true for the readable form ({@code prin1}), false for {@code princ}
	 * @return the printed text
	 */
	private String render(boolean escape) {
		StringBuilder sb = new StringBuilder(this.layout.openDelimiter()).append(this.layout.printName());
		for (int i = 0; i < this.slots.length; i++) {
			sb.append(" :")
				.append(this.layout.slotNames().get(i))
				.append(' ')
				.append(escape ? this.slots[i].print() : this.slots[i].display());
		}
		return sb.append(this.layout.closeDelimiter()).toString();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof LispInstance other && this.layout.tag().equals(other.layout.tag())
				&& Arrays.equals(this.slots, other.slots);
	}

	@Override
	public int hashCode() {
		return 31 * this.layout.tag().hashCode() + Arrays.hashCode(this.slots);
	}

	@Override
	public String toString() {
		return "LispInstance[" + this.layout.tag() + ", slots=" + Arrays.toString(this.slots) + "]";
	}

}
