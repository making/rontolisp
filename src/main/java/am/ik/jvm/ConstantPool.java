package am.ik.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.ConstantPoolBuilder;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.InterfaceMethodRefEntry;
import java.lang.classfile.constantpool.MethodRefEntry;
import java.lang.classfile.constantpool.NameAndTypeEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.lang.classfile.constantpool.StringEntry;
import java.lang.classfile.constantpool.Utf8Entry;

/**
 * The constant pool a generator mints its entries in: one {@code java.lang.classfile}
 * {@link ConstantPoolBuilder}, the MASTER pool a whole program's method bodies name
 * entries of, with the facades that mint an entry from names. Entries are deduplicated by
 * content, so two {@code Methodref}s to the same method are one entry.
 * <p>
 * The master pool is never written as it stands. It may grow past {@link #MAX_INDEX} -- a
 * class file's limit, not the builder's -- since an instruction names its entry itself
 * ({@link MethodCode}) and no index is encoded anywhere. The writer
 * ({@link JvmClassSplitter}) gives every class it writes a fresh pool holding exactly the
 * entries that class's members reference, re-minted there from these; one class, or a
 * class and its {@code $PartN} classes when one pool cannot hold them.
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

	/** Creates a new empty pool whose first entry takes index 1. */
	public ConstantPool() {
	}

	/**
	 * @return the master pool itself, for the entries no facade here mints
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
	 * Return the highest index taken, which is what a class file's
	 * {@code constant_pool_count} would have to cover if the pool were written whole.
	 * @return the entry count
	 */
	public int size() {
		return this.entries.size() - 1;
	}

	/**
	 * @param value the string
	 * @return its Utf8 entry
	 * @throws IllegalArgumentException when the value's modified UTF-8 form exceeds the
	 * 65535 bytes a {@code CONSTANT_Utf8} can hold
	 */
	public Utf8Entry utf8Entry(String value) {
		int bytes = modifiedUtf8Length(value);
		if (bytes > MAX_UTF8_BYTES) {
			// Refused here, at the site that minted it, rather than when some class
			// carrying it is written.
			throw new IllegalArgumentException("CONSTANT_Utf8 exceeds 65535 bytes: " + bytes);
		}
		return this.entries.utf8Entry(value);
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
	 * @param internalName a class's internal name ({@code java/util/Map}) or an array
	 * descriptor
	 * @return its Class entry
	 */
	public ClassEntry classEntry(String internalName) {
		return this.entries.classEntry(this.utf8Entry(internalName));
	}

	/**
	 * @param internalName a class's internal name or an array descriptor
	 * @return its Class entry
	 */
	public ClassEntry classEntry(Utf8Entry internalName) {
		return this.entries.classEntry(internalName);
	}

	/**
	 * @param owner the declaring class
	 * @param name the method's name
	 * @param descriptor its descriptor
	 * @return the Methodref
	 */
	public MethodRefEntry methodRef(ClassEntry owner, String name, String descriptor) {
		return this.entries.methodRefEntry(owner, this.nameAndType(name, descriptor));
	}

	/**
	 * @param owner the declaring class
	 * @param name the method's name
	 * @param descriptor its descriptor
	 * @return the Methodref
	 */
	public MethodRefEntry methodRef(ClassEntry owner, Utf8Entry name, Utf8Entry descriptor) {
		return this.entries.methodRefEntry(owner, this.entries.nameAndTypeEntry(name, descriptor));
	}

	/**
	 * @param owner the declaring class's internal name
	 * @param name the method's name
	 * @param descriptor its descriptor
	 * @return the Methodref
	 */
	public MethodRefEntry methodRef(String owner, String name, String descriptor) {
		return this.methodRef(this.classEntry(owner), name, descriptor);
	}

	/**
	 * @param owner the declaring interface's internal name
	 * @param name the method's name
	 * @param descriptor its descriptor
	 * @return the InterfaceMethodref
	 */
	public InterfaceMethodRefEntry interfaceMethodRef(String owner, String name, String descriptor) {
		return this.interfaceMethodRef(this.classEntry(owner), name, descriptor);
	}

	/**
	 * @param owner the declaring interface
	 * @param name the method's name
	 * @param descriptor its descriptor
	 * @return the InterfaceMethodref
	 */
	public InterfaceMethodRefEntry interfaceMethodRef(ClassEntry owner, String name, String descriptor) {
		return this.entries.interfaceMethodRefEntry(owner, this.nameAndType(name, descriptor));
	}

	/**
	 * @param owner the declaring interface
	 * @param name the method's name
	 * @param descriptor its descriptor
	 * @return the InterfaceMethodref
	 */
	public InterfaceMethodRefEntry interfaceMethodRef(ClassEntry owner, Utf8Entry name, Utf8Entry descriptor) {
		return this.entries.interfaceMethodRefEntry(owner, this.entries.nameAndTypeEntry(name, descriptor));
	}

	/**
	 * @param owner the declaring class
	 * @param name the field's name
	 * @param descriptor its descriptor
	 * @return the Fieldref
	 */
	public FieldRefEntry fieldRef(ClassEntry owner, String name, String descriptor) {
		return this.entries.fieldRefEntry(owner, this.nameAndType(name, descriptor));
	}

	/**
	 * @param owner the declaring class
	 * @param name the field's name
	 * @param descriptor its descriptor
	 * @return the Fieldref
	 */
	public FieldRefEntry fieldRef(ClassEntry owner, Utf8Entry name, Utf8Entry descriptor) {
		return this.entries.fieldRefEntry(owner, this.entries.nameAndTypeEntry(name, descriptor));
	}

	/**
	 * @param owner the declaring class's internal name
	 * @param name the field's name
	 * @param descriptor its descriptor
	 * @return the Fieldref
	 */
	public FieldRefEntry fieldRef(String owner, String name, String descriptor) {
		return this.fieldRef(this.classEntry(owner), name, descriptor);
	}

	/**
	 * @param value the string
	 * @return its String entry
	 */
	public StringEntry stringEntry(String value) {
		return this.entries.stringEntry(this.utf8Entry(value));
	}

	/**
	 * @param value the string
	 * @return its String entry
	 */
	public StringEntry stringEntry(Utf8Entry value) {
		return this.entries.stringEntry(value);
	}

	private NameAndTypeEntry nameAndType(String name, String descriptor) {
		return this.entries.nameAndTypeEntry(this.utf8Entry(name), this.utf8Entry(descriptor));
	}

}
