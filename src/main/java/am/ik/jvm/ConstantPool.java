package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * JVM class file constant pool builder. Entries are deduplicated by their serialized
 * bytes: adding the same constant twice returns the first entry instead of appending a
 * duplicate. Because a composite entry (Class/String/NameAndType/refs) embeds the u2
 * indexes of its already-deduplicated components, structural sharing falls out naturally
 * -- two {@code Methodref}s to the same method are byte-identical and collapse to one
 * entry. Duplicates are legal in the class format, so this is purely a size optimization,
 * but a decisive one: without it a large generated class wastes roughly half its pool on
 * repeats and can cross the 65535 class-format ceiling.
 */
public final class ConstantPool {

	private final ByteArrayOutputStream out = new ByteArrayOutputStream();

	private final Map<ByteBuffer, Constant> dedup = new HashMap<>();

	private final Map<Integer, String> utf8Values = new HashMap<>();

	private final Map<Integer, String> descriptors = new HashMap<>();

	private int size = 0;

	/** Creates a new empty constant pool. */
	public ConstantPool() {
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

	/**
	 * Add a constant entry to the pool, returning the existing entry when an identical
	 * one was added before.
	 * @param constantType the type of the constant
	 * @param constantDef a consumer that writes the constant data
	 * @return the constant entry
	 */
	public Constant add(ConstantType constantType, Consumer<ByteCodeWriter> constantDef) {
		return add(constantType, constantDef, false);
	}

	private Constant add(ConstantType constantType, Consumer<ByteCodeWriter> constantDef, boolean twoSlots) {
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final ByteCodeWriter out = new ByteCodeWriter(stream);
		out.write(constantType.value());
		constantDef.accept(out);
		byte[] bytes = stream.toByteArray();
		Constant existing = this.dedup.get(ByteBuffer.wrap(bytes));
		if (existing != null) {
			return existing;
		}
		try {
			this.out.write(bytes);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		Constant constant = new Constant(++this.size, constantType, bytes);
		if (twoSlots) {
			// Long and double constants take two constant pool entries
			this.size++;
		}
		this.dedup.put(ByteBuffer.wrap(bytes), constant);
		return constant;
	}

	/**
	 * Add a UTF-8 string constant.
	 * @param s the string value
	 * @return the UTF-8 constant entry
	 */
	public Utf8Constant addUtf8(String s) {
		Utf8Constant utf8 = new Utf8Constant(this.add(ConstantType.UTF8, o -> o.writeUtf8Info(s)));
		this.utf8Values.put(utf8.index(), s);
		return utf8;
	}

	/**
	 * Add a class constant.
	 * @param classUtf8 the UTF-8 constant for the class name
	 * @return the class constant entry
	 */
	public ClassConstant addClass(Utf8Constant classUtf8) {
		ClassConstant clazz = new ClassConstant(this.add(ConstantType.CLASS, o -> o.writeU2(classUtf8)));
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
				this.add(ConstantType.NAME_AND_TYPE, o -> o.writeU2(nameUtf8).writeU2(typeUtf8)));
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
				this.add(ConstantType.FIELDREF, o -> o.writeU2(clazz).writeU2(nameAndType)));
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
				this.add(ConstantType.METHODREF, o -> o.writeU2(clazz).writeU2(nameAndType)));
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
				this.add(ConstantType.INTERFACE_METHODREF, o -> o.writeU2(clazz).writeU2(nameAndType)));
		this.descriptors.put(ref.index(), this.descriptors.get(nameAndType.index()));
		return ref;
	}

	/**
	 * Add a string constant from a UTF-8 constant.
	 * @param utf8 the UTF-8 constant for the string value
	 * @return the string constant entry
	 */
	public StringConstant addString(Utf8Constant utf8) {
		StringConstant string = new StringConstant(this.add(ConstantType.STRING, o -> o.writeU2(utf8)));
		this.descriptors.put(string.index(), "Ljava/lang/String;");
		return string;
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
		IntegerConstant constant = new IntegerConstant(this.add(ConstantType.INTEGER, o -> o.writeU4(value)));
		this.descriptors.put(constant.index(), "I");
		return constant;
	}

	/**
	 * Add a long constant. Takes two constant pool entries.
	 * @param value the long value
	 * @return the long constant entry
	 */
	public LongConstant addLong(long value) {
		LongConstant constant = new LongConstant(this.add(ConstantType.LONG, o -> {
			o.writeU4((int) (value >>> 32));
			o.writeU4((int) value);
		}, true));
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
		DoubleConstant constant = new DoubleConstant(this.add(ConstantType.DOUBLE, o -> {
			o.writeU4((int) (bits >>> 32));
			o.writeU4((int) bits);
		}, true));
		this.descriptors.put(constant.index(), "D");
		return constant;
	}

	/**
	 * Return the number of entries in this constant pool.
	 * @return the entry count
	 */
	public int size() {
		return this.size;
	}

	/**
	 * Serialize this constant pool to a byte array.
	 * @return the serialized bytes
	 * @throws IllegalStateException when the pool exceeds the class-format limit of 65535
	 * entries (the u2 count would silently wrap, producing a corrupt class)
	 */
	public byte[] toByteArray() {
		if (this.size + 1 > 0xFFFF) {
			throw new IllegalStateException("constant pool overflow: " + this.size
					+ " entries exceed the JVM class-format limit of 65534; split the program");
		}
		final ByteArrayOutputStream stream = new ByteArrayOutputStream();
		final ByteCodeWriter out = new ByteCodeWriter(stream);
		out.writeU2(this.size + 1);
		out.write(this.out.toByteArray());
		return stream.toByteArray();
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
