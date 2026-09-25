package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * JVM class file constant pool builder. Entries are deduplicated by their content: adding
 * the same constant twice returns the first entry instead of appending a duplicate.
 * Because a composite entry (Class/String/NameAndType/refs) is keyed by the indexes of
 * its already-deduplicated components, structural sharing falls out naturally -- two
 * {@code Methodref}s to the same method collapse to one entry. Duplicates are legal in
 * the class format, so this is purely a size optimization, but a decisive one: without it
 * a large generated class wastes roughly half its pool on repeats.
 * <p>
 * Entries are held as data (tag, component indexes, payload), never as their u2-encoded
 * bytes, so a pool can describe more entries than one class file may carry without losing
 * which entry a component names. A pool made by {@link #unbounded()} grows past
 * {@link #MAX_INDEX}: its indexes are then only meaningful to an emitter whose every
 * index sink keeps the full value, and the class it describes has to be split over
 * several class files ({@link JvmClassSplitter}). {@link #toByteArray()} serializes a
 * pool that fits one class, and only such a pool.
 */
public final class ConstantPool {

	/**
	 * The largest usable constant pool index: {@code constant_pool_count} is a u2 holding
	 * the entry count plus one, so the last index is 65534.
	 */
	public static final int MAX_INDEX = 0xFFFE;

	/**
	 * Whether {@link #add} refuses the entry that would cross {@link #MAX_INDEX}. An
	 * emitter that writes an index as a u2 needs the refusal: the first symptom of an
	 * overflowing pool would otherwise be an instruction whose truncated operand points
	 * at an unrelated entry.
	 */
	private final boolean bounded;

	/**
	 * The entry at each index: slot 0 and the second slot of every long/double stay null.
	 */
	private final List<@Nullable Entry> slots = new ArrayList<>();

	private final Map<Entry, Constant> dedup = new HashMap<>();

	private final Map<Integer, String> utf8Values = new HashMap<>();

	private final java.util.Set<String> stringValues = new java.util.HashSet<>();

	private final Map<Integer, String> descriptors = new HashMap<>();

	private int size = 0;

	/** Creates a new empty constant pool that refuses to outgrow one class file. */
	public ConstantPool() {
		this(true);
	}

	private ConstantPool(boolean bounded) {
		this(bounded, 1);
	}

	private ConstantPool(boolean bounded, int firstIndex) {
		this.bounded = bounded;
		while (this.slots.size() < firstIndex) {
			this.slots.add(null);
		}
		this.size = firstIndex - 1;
	}

	/**
	 * Creates a new empty constant pool that may grow past {@link #MAX_INDEX}: one whose
	 * indexes the caller keeps at full width everywhere it writes them, and whose class
	 * it hands to {@link JvmClassSplitter} when the pool outgrows one class file.
	 * @return a new unbounded pool
	 */
	public static ConstantPool unbounded() {
		return new ConstantPool(false);
	}

	/**
	 * Creates an unbounded pool whose first entry takes {@code firstIndex} instead of 1
	 * -- a test instrument, never an output shape. Started past 65535, every index the
	 * emitter hands out is one no class file can carry, so a writer anywhere that cuts an
	 * operand to 16 bits names an entry that does not exist and the split fails loudly,
	 * instead of calling the wrong method in the one program large enough to reach it.
	 * {@link #size()} counts the skipped indexes, so such a pool never passes for one
	 * class's.
	 * @param firstIndex the index of the first entry added
	 * @return a new unbounded pool
	 */
	public static ConstantPool unboundedFrom(int firstIndex) {
		if (firstIndex < 1) {
			throw new IllegalArgumentException("a constant pool starts at index 1 or later: " + firstIndex);
		}
		return new ConstantPool(false, firstIndex);
	}

	/**
	 * Returns the type descriptor of the entry at {@code index}, as needed to compute an
	 * instruction's operand-stack effect from its constant-pool operand: a field
	 * descriptor for a Fieldref, a method descriptor for a Methodref/InterfaceMethodref,
	 * and the descriptor of the pushed value for the {@code ldc}-able constants
	 * (Integer/Float/Long/Double/String/Class).
	 * @param index the constant pool index
	 * @return the descriptor, or {@code null} when the entry has none (or was not added
	 * through this pool's typed factory methods)
	 */
	public @Nullable String descriptorOf(int index) {
		return this.descriptors.get(index);
	}

	private Constant addEntry(Entry entry) {
		Constant existing = this.dedup.get(entry);
		if (existing != null) {
			return existing;
		}
		boolean twoSlots = entry.type == ConstantType.LONG || entry.type == ConstantType.DOUBLE;
		if (this.bounded && this.size + (twoSlots ? 2 : 1) > MAX_INDEX) {
			// Refuse here rather than at serialization: an index past u2 is truncated by
			// every emit site that writes one (`(short) index`), so the FIRST symptom of
			// an overflowing pool is an instruction whose operand points at an unrelated
			// entry -- diagnosed downstream as a bogus operand-stack model failure.
			throw new ConstantPoolOverflowException("constant pool overflow: this class needs more than " + MAX_INDEX
					+ " constant pool entries, the JVM class-format limit; split the program");
		}
		Constant constant = new Constant(++this.size, entry.type, entry.bytes());
		this.slots.add(entry);
		if (twoSlots) {
			// Long and double constants take two constant pool entries
			this.size++;
			this.slots.add(null);
		}
		this.dedup.put(entry, constant);
		return constant;
	}

	/**
	 * Add a UTF-8 string constant.
	 * @param s the string value
	 * @return the UTF-8 constant entry
	 */
	public Utf8Constant addUtf8(String s) {
		ByteArrayOutputStream stream = new ByteArrayOutputStream();
		new ByteCodeWriter(stream).writeUtf8Info(s);
		Utf8Constant utf8 = new Utf8Constant(this.addEntry(Entry.data(ConstantType.UTF8, stream.toByteArray())));
		this.utf8Values.put(utf8.index(), s);
		return utf8;
	}

	/**
	 * Add a class constant.
	 * @param classUtf8 the UTF-8 constant for the class name
	 * @return the class constant entry
	 */
	public ClassConstant addClass(Utf8Constant classUtf8) {
		ClassConstant clazz = new ClassConstant(this.addEntry(Entry.ref(ConstantType.CLASS, classUtf8.index(), 0)));
		this.descriptors.put(clazz.index(), "Ljava/lang/Class;");
		return clazz;
	}

	/**
	 * Add a name-and-type constant.
	 * @param nameUtf8 the UTF-8 constant for the name
	 * @param typeUtf8 the UTF-8 constant for the type descriptor
	 * @return the name-and-type constant entry
	 */
	public NameAndTypeConstant addNameAndType(Utf8Constant nameUtf8, Utf8Constant typeUtf8) {
		NameAndTypeConstant nameAndType = new NameAndTypeConstant(
				this.addEntry(Entry.ref(ConstantType.NAME_AND_TYPE, nameUtf8.index(), typeUtf8.index())));
		this.descriptors.put(nameAndType.index(), this.utf8Values.get(typeUtf8.index()));
		return nameAndType;
	}

	/**
	 * Add a field reference constant.
	 * @param clazz the class constant
	 * @param nameAndType the name-and-type constant
	 * @return the field reference constant entry
	 */
	public FieldrefConstant addFieldref(ClassConstant clazz, NameAndTypeConstant nameAndType) {
		FieldrefConstant ref = new FieldrefConstant(
				this.addEntry(Entry.ref(ConstantType.FIELDREF, clazz.index(), nameAndType.index())));
		this.descriptors.put(ref.index(), this.descriptors.get(nameAndType.index()));
		return ref;
	}

	/**
	 * Add a method reference constant.
	 * @param clazz the class constant
	 * @param nameAndType the name-and-type constant
	 * @return the method reference constant entry
	 */
	public MethodrefConstant addMethodref(ClassConstant clazz, NameAndTypeConstant nameAndType) {
		MethodrefConstant ref = new MethodrefConstant(
				this.addEntry(Entry.ref(ConstantType.METHODREF, clazz.index(), nameAndType.index())));
		this.descriptors.put(ref.index(), this.descriptors.get(nameAndType.index()));
		return ref;
	}

	/**
	 * Add an interface method reference constant (tag 11), used by
	 * {@code invokeinterface}.
	 * @param clazz the interface class constant
	 * @param nameAndType the name-and-type constant
	 * @return the interface method reference constant entry
	 */
	public MethodrefConstant addInterfaceMethodref(ClassConstant clazz, NameAndTypeConstant nameAndType) {
		MethodrefConstant ref = new MethodrefConstant(
				this.addEntry(Entry.ref(ConstantType.INTERFACE_METHODREF, clazz.index(), nameAndType.index())));
		this.descriptors.put(ref.index(), this.descriptors.get(nameAndType.index()));
		return ref;
	}

	/**
	 * Add a string constant from a UTF-8 constant.
	 * @param utf8 the UTF-8 constant for the string value
	 * @return the string constant entry
	 */
	public StringConstant addString(Utf8Constant utf8) {
		StringConstant string = new StringConstant(this.addEntry(Entry.ref(ConstantType.STRING, utf8.index(), 0)));
		this.descriptors.put(string.index(), "Ljava/lang/String;");
		String value = this.utf8Values.get(utf8.index());
		if (value != null) {
			this.stringValues.add(value);
		}
		return string;
	}

	/**
	 * Whether a {@code CONSTANT_String} with this value has been added. Deliberately NOT
	 * a Utf8 probe: every method and field name is a Utf8, but only a value the emitted
	 * code can actually LOAD ({@code ldc}) is a String constant. A generator that has to
	 * know which of its own names the compiled program can produce at run time asks this.
	 * @param s the string value to probe
	 * @return true when the pool holds a string constant with that value
	 */
	public boolean hasStringConstant(String s) {
		return this.stringValues.contains(s);
	}

	/**
	 * Add a string constant from a string value.
	 * @param s the string value
	 * @return the string constant entry
	 */
	public StringConstant addString(String s) {
		return this.addString(this.addUtf8(s));
	}

	/**
	 * Add an integer constant.
	 * @param value the integer value
	 * @return the integer constant entry
	 */
	public IntegerConstant addInteger(int value) {
		IntegerConstant constant = new IntegerConstant(
				this.addEntry(Entry.data(ConstantType.INTEGER, ByteBuffer.allocate(4).putInt(value).array())));
		this.descriptors.put(constant.index(), "I");
		return constant;
	}

	/**
	 * Add a long constant. Takes two constant pool entries.
	 * @param value the long value
	 * @return the long constant entry
	 */
	public LongConstant addLong(long value) {
		LongConstant constant = new LongConstant(
				this.addEntry(Entry.data(ConstantType.LONG, ByteBuffer.allocate(8).putLong(value).array())));
		this.descriptors.put(constant.index(), "J");
		return constant;
	}

	/**
	 * Add a double constant. Takes two constant pool entries.
	 * @param value the double value
	 * @return the double constant entry
	 */
	public DoubleConstant addDouble(double value) {
		// Key by the serialized bits (doubleToLongBits), so -0.0 and 0.0 stay distinct
		// entries and every NaN shares the canonical bit pattern it serializes to.
		long bits = Double.doubleToLongBits(value);
		DoubleConstant constant = new DoubleConstant(
				this.addEntry(Entry.data(ConstantType.DOUBLE, ByteBuffer.allocate(8).putLong(bits).array())));
		this.descriptors.put(constant.index(), "D");
		return constant;
	}

	/**
	 * Return the number of entries in this constant pool -- the highest index taken,
	 * which is what a class file's {@code constant_pool_count} has to cover.
	 * @return the entry count
	 */
	public int size() {
		return this.size;
	}

	/**
	 * The type of the entry at {@code index}.
	 * @param index a constant pool index
	 * @return its type
	 * @throws IllegalArgumentException when no entry starts at {@code index}
	 */
	public ConstantType typeAt(int index) {
		return this.entryAt(index).type;
	}

	/**
	 * The first component index of a Class/String (the Utf8), NameAndType (the name),
	 * Fieldref/Methodref/InterfaceMethodref (the class) entry.
	 * @param index a constant pool index
	 * @return the component's index
	 */
	public int firstComponentAt(int index) {
		Entry entry = this.entryAt(index);
		if (entry.data != null) {
			throw new IllegalArgumentException("constant " + index + " (" + entry.type + ") has no component");
		}
		return entry.first;
	}

	/**
	 * The second component index of a NameAndType (the descriptor) or a
	 * Fieldref/Methodref/InterfaceMethodref (the name-and-type) entry.
	 * @param index a constant pool index
	 * @return the component's index
	 */
	public int secondComponentAt(int index) {
		Entry entry = this.entryAt(index);
		if (entry.type == ConstantType.CLASS || entry.type == ConstantType.STRING || entry.data != null) {
			throw new IllegalArgumentException("constant " + index + " (" + entry.type + ") has no second component");
		}
		return entry.second;
	}

	/**
	 * The serialized body of a component-free entry: the length-prefixed modified UTF-8
	 * of a Utf8, the big-endian bits of an Integer/Float/Long/Double.
	 * @param index a constant pool index
	 * @return a copy of the body bytes
	 */
	public byte[] dataAt(int index) {
		Entry entry = this.entryAt(index);
		if (entry.data == null) {
			throw new IllegalArgumentException("constant " + index + " (" + entry.type + ") is not a data entry");
		}
		return entry.data.clone();
	}

	/**
	 * The string value of the Utf8 entry at {@code index}.
	 * @param index a constant pool index
	 * @return its value
	 */
	public String utf8At(int index) {
		String value = this.utf8Values.get(index);
		if (value == null) {
			throw new IllegalArgumentException("constant " + index + " is not a Utf8 entry");
		}
		return value;
	}

	private Entry entryAt(int index) {
		Entry entry = index > 0 && index < this.slots.size() ? this.slots.get(index) : null;
		if (entry == null) {
			throw new IllegalArgumentException("no constant pool entry starts at index " + index);
		}
		return entry;
	}

	/**
	 * Serialize this constant pool to a byte array.
	 * @return the serialized bytes
	 * @throws IllegalStateException when the pool exceeds the class-format limit of 65534
	 * entries (the u2 count would silently wrap, producing a corrupt class) -- a bounded
	 * pool's {@code add} already refuses to cross it, so for one this is a backstop
	 */
	public byte[] toByteArray() {
		if (this.size > MAX_INDEX) {
			throw new ConstantPoolOverflowException("constant pool overflow: " + this.size
					+ " entries exceed the JVM class-format limit of " + MAX_INDEX + "; split the program");
		}
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final ByteCodeWriter out = new ByteCodeWriter(stream);
		out.writeU2(this.size + 1);
		for (Entry entry : this.slots) {
			if (entry != null) {
				out.write(entry.bytes());
			}
		}
		return stream.toByteArray();
	}

	/**
	 * One entry as data: its type, and either its component indexes (the reference types)
	 * or its serialized body (Utf8 and the numeric constants). Equality is by content,
	 * which is what the pool deduplicates on.
	 */
	private static final class Entry {

		private final ConstantType type;

		private final int first;

		private final int second;

		private final byte @Nullable [] data;

		private final int hash;

		private Entry(ConstantType type, int first, int second, byte @Nullable [] data) {
			this.type = type;
			this.first = first;
			this.second = second;
			this.data = data;
			this.hash = 31 * (31 * (31 * type.hashCode() + first) + second)
					+ (data == null ? 0 : java.util.Arrays.hashCode(data));
		}

		static Entry ref(ConstantType type, int first, int second) {
			return new Entry(type, first, second, null);
		}

		static Entry data(ConstantType type, byte[] data) {
			return new Entry(type, 0, 0, data);
		}

		/**
		 * The entry as a class file serializes it: the tag, then the body. Only exact for
		 * component indexes that fit a u2, which a serialized pool guarantees.
		 */
		byte[] bytes() {
			if (this.data != null) {
				byte[] bytes = new byte[1 + this.data.length];
				bytes[0] = (byte) this.type.value();
				System.arraycopy(this.data, 0, bytes, 1, this.data.length);
				return bytes;
			}
			if (this.type == ConstantType.CLASS || this.type == ConstantType.STRING) {
				return new byte[] { (byte) this.type.value(), (byte) (this.first >>> 8), (byte) this.first };
			}
			return new byte[] { (byte) this.type.value(), (byte) (this.first >>> 8), (byte) this.first,
					(byte) (this.second >>> 8), (byte) this.second };
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Entry other && this.type == other.type && this.first == other.first
					&& this.second == other.second && java.util.Arrays.equals(this.data, other.data);
		}

		@Override
		public int hashCode() {
			return this.hash;
		}

	}

	/**
	 * A constant pool entry.
	 */
	public static class Constant {

		private final int index;

		private final ConstantType type;

		private final byte[] bytes;

		/**
		 * Create a new constant entry.
		 * @param index the constant pool index
		 * @param type the constant type
		 * @param bytes the raw bytes
		 */
		public Constant(int index, ConstantType type, byte[] bytes) {
			this.index = index;
			this.type = type;
			this.bytes = bytes;
		}

		/**
		 * Return the constant pool index.
		 * @return the index
		 */
		public int index() {
			return index;
		}

		/**
		 * Return the index as a 2-byte big-endian array.
		 * @return the index bytes
		 */
		public byte[] indexAsU2() {
			return ByteBuffer.allocate(2).putShort((short) index).array();
		}

		/**
		 * Return the constant type.
		 * @return the type
		 */
		public ConstantType type() {
			return type;
		}

		/**
		 * Return the raw bytes of this constant.
		 * @return the bytes
		 */
		public byte[] bytes() {
			return bytes;
		}

	}

	/**
	 * A UTF-8 constant pool entry.
	 */
	public static class Utf8Constant extends Constant {

		/**
		 * Create a UTF-8 constant from a base constant.
		 * @param constant the base constant
		 */
		public Utf8Constant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * A class constant pool entry.
	 */
	public static class ClassConstant extends Constant {

		/**
		 * Create a class constant from a base constant.
		 * @param constant the base constant
		 */
		public ClassConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * A name-and-type constant pool entry.
	 */
	public static class NameAndTypeConstant extends Constant {

		/**
		 * Create a name-and-type constant from a base constant.
		 * @param constant the base constant
		 */
		public NameAndTypeConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * A field reference constant pool entry.
	 */
	public static class FieldrefConstant extends Constant {

		/**
		 * Create a field reference constant from a base constant.
		 * @param constant the base constant
		 */
		public FieldrefConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * A method reference constant pool entry.
	 */
	public static class MethodrefConstant extends Constant {

		/**
		 * Create a method reference constant from a base constant.
		 * @param constant the base constant
		 */
		public MethodrefConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * A string constant pool entry.
	 */
	public static class StringConstant extends Constant {

		/**
		 * Create a string constant from a base constant.
		 * @param constant the base constant
		 */
		public StringConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * An integer constant pool entry.
	 */
	public static class IntegerConstant extends Constant {

		/**
		 * Create an integer constant from a base constant.
		 * @param constant the base constant
		 */
		public IntegerConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * A long constant pool entry.
	 */
	public static class LongConstant extends Constant {

		/**
		 * Create a long constant from a base constant.
		 * @param constant the base constant
		 */
		public LongConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

	/**
	 * A double constant pool entry.
	 */
	public static class DoubleConstant extends Constant {

		/**
		 * Create a double constant from a base constant.
		 * @param constant the base constant
		 */
		public DoubleConstant(Constant constant) {
			super(constant.index, constant.type(), constant.bytes());
		}

	}

}
