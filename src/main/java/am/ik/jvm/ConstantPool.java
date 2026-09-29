package am.ik.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.DoubleEntry;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.FloatEntry;
import java.lang.classfile.constantpool.IntegerEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.LongEntry;
import java.lang.classfile.constantpool.MemberRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.HashSet;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The constant pool a generator mints its entries in: one {@code java.lang.classfile}
 * {@link ConstantPoolBuilder}, the MASTER pool a whole program's method bodies index
 * into, wrapped in the typed constants the generators hand around. Entries are
 * deduplicated by content, so two {@code Methodref}s to the same method are one entry.
 * <p>
 * The master pool is never written as it stands. It may grow past {@link #MAX_INDEX} -- a
 * class file's limit, not the builder's -- so its indexes are only meaningful to an
 * emitter whose every index sink keeps the full value (a u2's high part whole). The
 * writer ({@link JvmClassSplitter}) gives every class it writes a fresh pool holding
 * exactly the entries that class's members reference, re-minted there from these; one
 * class, or a class and its {@code $PartN} classes when one pool cannot hold them.
 */
public final class ConstantPool {

	/**
	 * The largest usable constant pool index: {@code constant_pool_count} is a u2 holding
	 * the entry count plus one, so the last index is 65534.
	 */
	public static final int MAX_INDEX = 0xFFFE;

	/**
	 * The most bytes one {@code CONSTANT_Utf8} holds (JVMS 4.4.7): its length is a u2.
	 */
	private static final int MAX_UTF8_BYTES = 0xFFFF;

	private final ConstantPoolBuilder entries = ConstantPoolBuilder.of();

	private final Set<String> stringValues = new HashSet<>();

	/** Creates a new empty pool whose first entry takes index 1. */
	public ConstantPool() {
	}

	/**
	 * Creates a pool whose first entry takes {@code firstIndex} instead of 1 -- a test
	 * instrument, never an output shape. Started past 65535, every index the emitter
	 * hands out is one no class file can carry, so an emitter that cuts an operand to 16
	 * bits names an entry that is not the one it meant, and the write fails loudly
	 * instead of calling the wrong method in the one program large enough to reach it.
	 * The indexes before it hold filler entries nothing references, which no written
	 * class carries; {@link #size()} counts them.
	 * @param firstIndex the index of the first entry added
	 * @return a new pool
	 */
	public static ConstantPool startingAt(int firstIndex) {
		if (firstIndex < 1) {
			throw new IllegalArgumentException("a constant pool starts at index 1 or later: " + firstIndex);
		}
		ConstantPool pool = new ConstantPool();
		for (int i = 1; i < firstIndex; i++) {
			pool.entries.utf8Entry("\0filler " + i);
		}
		return pool;
	}

	/**
	 * @return the master pool itself, for an emitter that works in
	 * {@code java.lang.classfile} entries
	 */
	public ConstantPoolBuilder entries() {
		return this.entries;
	}

	/**
	 * The entry at an index.
	 * @param index a master-pool index
	 * @return its entry
	 * @throws IllegalArgumentException when no entry starts at {@code index}
	 */
	public PoolEntry entryAt(int index) {
		if (index < 1 || index >= this.entries.size()) {
			throw new IllegalArgumentException("no constant pool entry starts at index " + index);
		}
		PoolEntry entry = this.entries.entryByIndex(index);
		if (entry == null) {
			throw new IllegalArgumentException("no constant pool entry starts at index " + index);
		}
		return entry;
	}

	/**
	 * Returns the type descriptor of the entry at {@code index}, as needed to compute an
	 * instruction's operand-stack effect from its constant-pool operand: a field
	 * descriptor for a Fieldref, a method descriptor for a Methodref/InterfaceMethodref,
	 * and the descriptor of the pushed value for the {@code ldc}-able constants
	 * (Integer/Float/Long/Double/String/Class).
	 * @param index the constant pool index
	 * @return the descriptor, or {@code null} when the entry has none
	 */
	public @Nullable String descriptorOf(int index) {
		PoolEntry entry = this.entryAt(index);
		return switch (entry) {
			case MemberRefEntry ref -> ref.type().stringValue();
			case NameAndTypeEntry nameAndType -> nameAndType.type().stringValue();
			case ClassEntry ignored -> "Ljava/lang/Class;";
			case StringEntry ignored -> "Ljava/lang/String;";
			case IntegerEntry ignored -> "I";
			case FloatEntry ignored -> "F";
			case LongEntry ignored -> "J";
			case DoubleEntry ignored -> "D";
			default -> null;
		};
	}

	/**
	 * Add a UTF-8 string constant.
	 * @param s the string value
	 * @return the UTF-8 constant entry
	 * @throws IllegalArgumentException when the value's modified UTF-8 form exceeds the
	 * 65535 bytes a {@code CONSTANT_Utf8} can hold
	 */
	public Utf8Constant addUtf8(String s) {
		int bytes = modifiedUtf8Length(s);
		if (bytes > MAX_UTF8_BYTES) {
			// Refused here, at the site that minted it, rather than when some class
			// carrying it is written.
			throw new IllegalArgumentException("CONSTANT_Utf8 exceeds 65535 bytes: " + bytes);
		}
		return new Utf8Constant(this.entries.utf8Entry(s));
	}

	/**
	 * The length of a string's modified UTF-8 form: U+0000 is two bytes, a supplementary
	 * character is its surrogate pair of three bytes each.
	 */
	private static int modifiedUtf8Length(String s) {
		int length = s.length();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c == 0 || c > 0x7F) {
				length += c <= 0x7FF ? 1 : 2;
			}
		}
		return length;
	}

	/**
	 * Add a class constant.
	 * @param classUtf8 the UTF-8 constant for the class name
	 * @return the class constant entry
	 */
	public ClassConstant addClass(Utf8Constant classUtf8) {
		return new ClassConstant(this.entries.classEntry(classUtf8.entry()));
	}

	/**
	 * Add a name-and-type constant.
	 * @param nameUtf8 the UTF-8 constant for the name
	 * @param typeUtf8 the UTF-8 constant for the type descriptor
	 * @return the name-and-type constant entry
	 */
	public NameAndTypeConstant addNameAndType(Utf8Constant nameUtf8, Utf8Constant typeUtf8) {
		return new NameAndTypeConstant(this.entries.nameAndTypeEntry(nameUtf8.entry(), typeUtf8.entry()));
	}

	/**
	 * Add a field reference constant.
	 * @param clazz the class constant
	 * @param nameAndType the name-and-type constant
	 * @return the field reference constant entry
	 */
	public FieldrefConstant addFieldref(ClassConstant clazz, NameAndTypeConstant nameAndType) {
		return new FieldrefConstant(this.entries.fieldRefEntry(clazz.entry(), nameAndType.entry()));
	}

	/**
	 * Add a method reference constant.
	 * @param clazz the class constant
	 * @param nameAndType the name-and-type constant
	 * @return the method reference constant entry
	 */
	public MethodrefConstant addMethodref(ClassConstant clazz, NameAndTypeConstant nameAndType) {
		return new MethodrefConstant(this.entries.methodRefEntry(clazz.entry(), nameAndType.entry()));
	}

	/**
	 * Add an interface method reference constant (tag 11), used by
	 * {@code invokeinterface}.
	 * @param clazz the interface class constant
	 * @param nameAndType the name-and-type constant
	 * @return the interface method reference constant entry
	 */
	public MethodrefConstant addInterfaceMethodref(ClassConstant clazz, NameAndTypeConstant nameAndType) {
		return new MethodrefConstant(this.entries.interfaceMethodRefEntry(clazz.entry(), nameAndType.entry()));
	}

	/**
	 * Add a string constant from a UTF-8 constant.
	 * @param utf8 the UTF-8 constant for the string value
	 * @return the string constant entry
	 */
	public StringConstant addString(Utf8Constant utf8) {
		this.stringValues.add(utf8.entry().stringValue());
		return new StringConstant(this.entries.stringEntry(utf8.entry()));
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
		return new IntegerConstant(this.entries.intEntry(value));
	}

	/**
	 * Add a long constant. Takes two constant pool entries.
	 * @param value the long value
	 * @return the long constant entry
	 */
	public LongConstant addLong(long value) {
		return new LongConstant(this.entries.longEntry(value));
	}

	/**
	 * Add a double constant. Takes two constant pool entries. {@code -0.0} and
	 * {@code 0.0} stay distinct entries.
	 * @param value the double value
	 * @return the double constant entry
	 */
	public DoubleConstant addDouble(double value) {
		return new DoubleConstant(this.entries.doubleEntry(value));
	}

	/**
	 * Return the highest index taken, which is what a class file's
	 * {@code constant_pool_count} would have to cover if the pool were written whole.
	 * @return the entry count
	 */
	public int size() {
		return this.entries.size() - 1;
	}

	/**
	 * The type of the entry at {@code index}.
	 * @param index a constant pool index
	 * @return its type
	 * @throws IllegalArgumentException when no entry starts at {@code index}, or it is of
	 * a kind the generators never mint
	 */
	public ConstantType typeAt(int index) {
		return switch (this.entryAt(index)) {
			case Utf8Entry ignored -> ConstantType.UTF8;
			case IntegerEntry ignored -> ConstantType.INTEGER;
			case FloatEntry ignored -> ConstantType.FLOAT;
			case LongEntry ignored -> ConstantType.LONG;
			case DoubleEntry ignored -> ConstantType.DOUBLE;
			case ClassEntry ignored -> ConstantType.CLASS;
			case StringEntry ignored -> ConstantType.STRING;
			case FieldRefEntry ignored -> ConstantType.FIELDREF;
			case MethodRefEntry ignored -> ConstantType.METHODREF;
			case InterfaceMethodRefEntry ignored -> ConstantType.INTERFACE_METHODREF;
			case NameAndTypeEntry ignored -> ConstantType.NAME_AND_TYPE;
			case PoolEntry other -> throw new IllegalArgumentException("constant " + index + " is a " + other);
		};
	}

	/**
	 * The first component index of a Class/String (the Utf8), NameAndType (the name),
	 * Fieldref/Methodref/InterfaceMethodref (the class) entry.
	 * @param index a constant pool index
	 * @return the component's index
	 */
	public int firstComponentAt(int index) {
		return switch (this.entryAt(index)) {
			case ClassEntry clazz -> clazz.name().index();
			case StringEntry string -> string.utf8().index();
			case NameAndTypeEntry nameAndType -> nameAndType.name().index();
			case MemberRefEntry ref -> ref.owner().index();
			case PoolEntry other -> throw new IllegalArgumentException("constant " + index + " has no component");
		};
	}

	/**
	 * The second component index of a NameAndType (the descriptor) or a
	 * Fieldref/Methodref/InterfaceMethodref (the name-and-type) entry.
	 * @param index a constant pool index
	 * @return the component's index
	 */
	public int secondComponentAt(int index) {
		return switch (this.entryAt(index)) {
			case NameAndTypeEntry nameAndType -> nameAndType.type().index();
			case MemberRefEntry ref -> ref.nameAndType().index();
			case PoolEntry other ->
				throw new IllegalArgumentException("constant " + index + " has no second component");
		};
	}

	/**
	 * The string value of the Utf8 entry at {@code index}.
	 * @param index a constant pool index
	 * @return its value
	 */
	public String utf8At(int index) {
		if (this.entryAt(index) instanceof Utf8Entry utf8) {
			return utf8.stringValue();
		}
		throw new IllegalArgumentException("constant " + index + " is not a Utf8 entry");
	}

	/**
	 * A constant pool entry: the master-pool entry, by the kind a generator minted it as.
	 */
	public static class Constant {

		private final PoolEntry entry;

		private final ConstantType type;

		Constant(PoolEntry entry, ConstantType type) {
			this.entry = entry;
			this.type = type;
		}

		/**
		 * Return the constant pool index.
		 * @return the index
		 */
		public int index() {
			return this.entry.index();
		}

		/**
		 * Return the constant type.
		 * @return the type
		 */
		public ConstantType type() {
			return this.type;
		}

		/**
		 * @return the master-pool entry
		 */
		public PoolEntry entry() {
			return this.entry;
		}

	}

	/**
	 * A UTF-8 constant pool entry.
	 */
	public static final class Utf8Constant extends Constant {

		Utf8Constant(Utf8Entry entry) {
			super(entry, ConstantType.UTF8);
		}

		@Override
		public Utf8Entry entry() {
			return (Utf8Entry) super.entry();
		}

	}

	/**
	 * A class constant pool entry.
	 */
	public static final class ClassConstant extends Constant {

		ClassConstant(ClassEntry entry) {
			super(entry, ConstantType.CLASS);
		}

		@Override
		public ClassEntry entry() {
			return (ClassEntry) super.entry();
		}

	}

	/**
	 * A name-and-type constant pool entry.
	 */
	public static final class NameAndTypeConstant extends Constant {

		NameAndTypeConstant(NameAndTypeEntry entry) {
			super(entry, ConstantType.NAME_AND_TYPE);
		}

		@Override
		public NameAndTypeEntry entry() {
			return (NameAndTypeEntry) super.entry();
		}

	}

	/**
	 * A field reference constant pool entry.
	 */
	public static final class FieldrefConstant extends Constant {

		FieldrefConstant(FieldRefEntry entry) {
			super(entry, ConstantType.FIELDREF);
		}

		@Override
		public FieldRefEntry entry() {
			return (FieldRefEntry) super.entry();
		}

	}

	/**
	 * A method reference constant pool entry: a {@code Methodref}, or an
	 * {@code InterfaceMethodref} for {@code invokeinterface} and an interface's static
	 * methods.
	 */
	public static final class MethodrefConstant extends Constant {

		MethodrefConstant(MemberRefEntry entry) {
			super(entry, entry instanceof InterfaceMethodRefEntry ? ConstantType.INTERFACE_METHODREF
					: ConstantType.METHODREF);
		}

		@Override
		public MemberRefEntry entry() {
			return (MemberRefEntry) super.entry();
		}

	}

	/**
	 * A string constant pool entry.
	 */
	public static final class StringConstant extends Constant {

		StringConstant(StringEntry entry) {
			super(entry, ConstantType.STRING);
		}

		@Override
		public StringEntry entry() {
			return (StringEntry) super.entry();
		}

	}

	/**
	 * An integer constant pool entry.
	 */
	public static final class IntegerConstant extends Constant {

		IntegerConstant(IntegerEntry entry) {
			super(entry, ConstantType.INTEGER);
		}

		@Override
		public IntegerEntry entry() {
			return (IntegerEntry) super.entry();
		}

	}

	/**
	 * A long constant pool entry.
	 */
	public static final class LongConstant extends Constant {

		LongConstant(LongEntry entry) {
			super(entry, ConstantType.LONG);
		}

		@Override
		public LongEntry entry() {
			return (LongEntry) super.entry();
		}

	}

	/**
	 * A double constant pool entry.
	 */
	public static final class DoubleConstant extends Constant {

		DoubleConstant(DoubleEntry entry) {
			super(entry, ConstantType.DOUBLE);
		}

		@Override
		public DoubleEntry entry() {
			return (DoubleEntry) super.entry();
		}

	}

}
