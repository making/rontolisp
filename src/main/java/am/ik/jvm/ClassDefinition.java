package am.ik.jvm;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;

import org.jspecify.annotations.Nullable;

/**
 * A class described as data before it is written: its constant pool, header, fields and
 * methods, each method body a list of code bytes whose constant-pool operands are kept at
 * full width. The shape is the one every generated class has -- attribute-free fields,
 * one {@code Code} attribute per method whose only sub-attribute is an optional
 * {@code LineNumberTable}, no class attributes -- which is also what
 * {@link JvmClassShaker} and {@link StackMapAugmenter} accept.
 * <p>
 * A definition whose pool fits one class file is written by {@link #toBytes()}; one whose
 * pool outgrew it is handed to {@link JvmClassSplitter}, which spreads its methods over
 * several class files. Keeping the class as data until that decision is what makes the
 * second outcome possible at all: once written, an operand past 65535 would already have
 * lost the entry it named.
 */
public final class ClassDefinition {

	private final ConstantPool cp;

	private final int accessFlags;

	private final ClassConstant thisClass;

	private final ClassConstant superClass;

	private final Utf8Constant codeName;

	private final @Nullable Utf8Constant lineNumberTableName;

	private final List<ClassConstant> interfaces;

	private final List<Field> fields;

	private final List<Method> methods;

	private ClassDefinition(Builder builder) {
		this.cp = builder.cp;
		this.accessFlags = builder.accessFlags;
		this.thisClass = builder.thisClass;
		this.superClass = builder.superClass;
		this.codeName = builder.codeName;
		this.lineNumberTableName = builder.lineNumberTableName;
		this.interfaces = List.copyOf(builder.interfaces);
		this.fields = List.copyOf(builder.fields);
		this.methods = List.copyOf(builder.methods);
		if (this.lineNumberTableName == null) {
			for (Method method : this.methods) {
				if (!method.lineNumbers().isEmpty()) {
					throw new IllegalStateException("method " + this.cp.utf8At(method.name().index())
							+ " carries line numbers, but the definition names no LineNumberTable attribute");
				}
			}
		}
	}

	/**
	 * Starts a definition.
	 * @param cp the pool every index in the definition refers to
	 * @param accessFlags the class's access flags
	 * @param thisClass the class itself
	 * @param superClass its superclass
	 * @param codeName the {@code "Code"} Utf8 every method's attribute is named by
	 * @return a builder
	 */
	public static Builder builder(ConstantPool cp, int accessFlags, ClassConstant thisClass, ClassConstant superClass,
			Utf8Constant codeName) {
		return new Builder(cp, accessFlags, thisClass, superClass, codeName);
	}

	/**
	 * @return the pool every index in the definition refers to
	 */
	public ConstantPool cp() {
		return this.cp;
	}

	/**
	 * @return the class's access flags
	 */
	public int accessFlags() {
		return this.accessFlags;
	}

	/**
	 * @return the class itself
	 */
	public ClassConstant thisClass() {
		return this.thisClass;
	}

	/**
	 * @return its superclass
	 */
	public ClassConstant superClass() {
		return this.superClass;
	}

	/**
	 * @return the {@code "Code"} Utf8
	 */
	public Utf8Constant codeName() {
		return this.codeName;
	}

	/**
	 * @return the {@code "LineNumberTable"} Utf8 every method's line numbers are named
	 * by, or {@code null} when no method carries any
	 */
	public @Nullable Utf8Constant lineNumberTableName() {
		return this.lineNumberTableName;
	}

	/**
	 * @return the implemented interfaces, in declaration order
	 */
	public List<ClassConstant> interfaces() {
		return this.interfaces;
	}

	/**
	 * @return the fields, in declaration order
	 */
	public List<Field> fields() {
		return this.fields;
	}

	/**
	 * @return the methods, in declaration order
	 */
	public List<Method> methods() {
		return this.methods;
	}

	/**
	 * Writes the definition as one class file (major version 50, the version the
	 * generators emit before {@link StackMapAugmenter} raises it).
	 * @return the class file
	 * @throws IllegalStateException when the pool does not fit one class file
	 * @throws IllegalArgumentException when a method's code exceeds 65535 bytes
	 */
	public byte[] toBytes() {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ByteCodeWriter w = new ByteCodeWriter(out);
		w.write(0xCA, 0xFE, 0xBA, 0xBE).writeVersion(0, 50).writeConstantPool(this.cp);
		w.writeU2(this.accessFlags).writeU2(this.thisClass).writeU2(this.superClass);
		w.writeU2(this.interfaces.size());
		for (ClassConstant iface : this.interfaces) {
			w.writeU2(iface);
		}
		w.writeU2(this.fields.size());
		for (Field field : this.fields) {
			w.writeU2(field.access()).writeU2(field.name()).writeU2(field.descriptor()).writeU2(0);
		}
		w.writeU2(this.methods.size());
		for (Method method : this.methods) {
			byte[] code = method.codeBytes(this.cp);
			List<ByteCodeWriter.LineNumberEntry> lines = method.lineNumbers();
			w.writeU2(method.access()).writeU2(method.name()).writeU2(method.descriptor());
			w.writeU2(1).writeU2(this.codeName);
			w.writeU4(
					2 + 2 + 4 + code.length + 2 + 8 * method.exceptionTable().size() + 2 + lineNumberTableSize(lines));
			w.writeU2(method.maxStack()).writeU2(method.maxLocals()).writeU4(code.length).write(code);
			w.writeExceptionTable(method.exceptionTable());
			if (lines.isEmpty()) {
				w.writeU2(0);
			}
			else {
				w.writeU2(1).writeU2(Objects.requireNonNull(this.lineNumberTableName));
				w.writeU4(2 + 4 * lines.size()).writeLineNumberTable(lines);
			}
		}
		w.writeU2(0);
		return out.toByteArray();
	}

	/**
	 * The bytes a method's {@code LineNumberTable} sub-attribute adds to its {@code Code}
	 * attribute: none without entries, else its name index, length and table.
	 * @param lines the method's entries
	 * @return the attribute's whole size
	 */
	static int lineNumberTableSize(List<ByteCodeWriter.LineNumberEntry> lines) {
		return lines.isEmpty() ? 0 : 2 + 4 + 2 + 4 * lines.size();
	}

	/**
	 * A field: attribute-free.
	 *
	 * @param access its access flags
	 * @param name its name
	 * @param descriptor its descriptor
	 */
	public record Field(int access, Utf8Constant name, Utf8Constant descriptor) {
	}

	/**
	 * A method with its single {@code Code} attribute.
	 *
	 * @param access its access flags
	 * @param name its name
	 * @param descriptor its descriptor
	 * @param maxStack the declared {@code max_stack}
	 * @param maxLocals the declared {@code max_locals}
	 * @param code the body, one element per byte; a constant-pool operand's high part may
	 * exceed a byte
	 * @param exceptionTable the handlers, in dispatch order
	 * @param lineNumbers the {@code LineNumberTable} entries, in ascending pc order;
	 * empty for a method that carries none
	 */
	public record Method(int access, Utf8Constant name, Utf8Constant descriptor, int maxStack, int maxLocals,
			List<Integer> code, List<ByteCodeWriter.ExceptionTableEntry> exceptionTable,
			List<ByteCodeWriter.LineNumberEntry> lineNumbers) {

		/**
		 * Copies the body, the handler table and the line numbers.
		 */
		public Method {
			code = List.copyOf(code);
			exceptionTable = List.copyOf(exceptionTable);
			lineNumbers = List.copyOf(lineNumbers);
		}

		/**
		 * A method without line numbers.
		 * @param access its access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 * @param maxStack the declared {@code max_stack}
		 * @param maxLocals the declared {@code max_locals}
		 * @param code the body, one element per byte
		 * @param exceptionTable the handlers, in dispatch order
		 */
		public Method(int access, Utf8Constant name, Utf8Constant descriptor, int maxStack, int maxLocals,
				List<Integer> code, List<ByteCodeWriter.ExceptionTableEntry> exceptionTable) {
			this(access, name, descriptor, maxStack, maxLocals, code, exceptionTable, List.of());
		}

		/**
		 * The code array as the class file carries it: each element's low eight bits.
		 * @param cp the pool the name decodes against, for the error message
		 * @return the code bytes
		 * @throws IllegalArgumentException past the 65535-byte limit (JVMS 4.7.3)
		 */
		byte[] codeBytes(ConstantPool cp) {
			if (this.code.size() > 0xFFFF) {
				// Writing a longer body produces a class every JVM rejects at load time
				// with a message that no longer names the culprit; fail here instead.
				throw new IllegalArgumentException("method " + cp.utf8At(this.name.index())
						+ ": method code exceeds the JVM's 65535-byte limit: " + this.code.size());
			}
			byte[] bytes = new byte[this.code.size()];
			for (int i = 0; i < bytes.length; i++) {
				bytes[i] = (byte) (int) this.code.get(i);
			}
			return bytes;
		}

	}

	/**
	 * Collects a definition in declaration order.
	 */
	public static final class Builder {

		private final ConstantPool cp;

		private final int accessFlags;

		private final ClassConstant thisClass;

		private final ClassConstant superClass;

		private final Utf8Constant codeName;

		private @Nullable Utf8Constant lineNumberTableName;

		private final List<ClassConstant> interfaces = new ArrayList<>();

		private final List<Field> fields = new ArrayList<>();

		private final List<Method> methods = new ArrayList<>();

		private Builder(ConstantPool cp, int accessFlags, ClassConstant thisClass, ClassConstant superClass,
				Utf8Constant codeName) {
			this.cp = Objects.requireNonNull(cp);
			this.accessFlags = accessFlags;
			this.thisClass = Objects.requireNonNull(thisClass);
			this.superClass = Objects.requireNonNull(superClass);
			this.codeName = Objects.requireNonNull(codeName);
		}

		/**
		 * Names the {@code LineNumberTable} attribute, which a method's line numbers need
		 * and a class whose methods carry none must not mint: the Utf8 is a pool entry.
		 * @param name the {@code "LineNumberTable"} Utf8
		 * @return this builder
		 */
		public Builder lineNumberTableName(Utf8Constant name) {
			this.lineNumberTableName = Objects.requireNonNull(name);
			return this;
		}

		/**
		 * @param iface an implemented interface
		 * @return this builder
		 */
		public Builder addInterface(ClassConstant iface) {
			this.interfaces.add(Objects.requireNonNull(iface));
			return this;
		}

		/**
		 * @param access the field's access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 * @return this builder
		 */
		public Builder addField(int access, Utf8Constant name, Utf8Constant descriptor) {
			this.fields.add(new Field(access, Objects.requireNonNull(name), Objects.requireNonNull(descriptor)));
			return this;
		}

		/**
		 * @param access the method's access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 * @param maxStack the declared {@code max_stack}
		 * @param maxLocals the declared {@code max_locals}
		 * @param code the body, one element per byte
		 * @param exceptionTable the handlers, in dispatch order
		 * @return this builder
		 */
		public Builder addMethod(int access, Utf8Constant name, Utf8Constant descriptor, int maxStack, int maxLocals,
				List<Integer> code, List<ByteCodeWriter.ExceptionTableEntry> exceptionTable) {
			return this.addMethod(access, name, descriptor, maxStack, maxLocals, code, exceptionTable, List.of());
		}

		/**
		 * @param access the method's access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 * @param maxStack the declared {@code max_stack}
		 * @param maxLocals the declared {@code max_locals}
		 * @param code the body, one element per byte
		 * @param exceptionTable the handlers, in dispatch order
		 * @param lineNumbers the {@code LineNumberTable} entries, in ascending pc order
		 * (empty for none; any at all needs {@link #lineNumberTableName})
		 * @return this builder
		 */
		public Builder addMethod(int access, Utf8Constant name, Utf8Constant descriptor, int maxStack, int maxLocals,
				List<Integer> code, List<ByteCodeWriter.ExceptionTableEntry> exceptionTable,
				List<ByteCodeWriter.LineNumberEntry> lineNumbers) {
			this.methods.add(new Method(access, Objects.requireNonNull(name), Objects.requireNonNull(descriptor),
					maxStack, maxLocals, code, exceptionTable, lineNumbers));
			return this;
		}

		/**
		 * @return the definition
		 */
		public ClassDefinition build() {
			return new ClassDefinition(this);
		}

	}

}
