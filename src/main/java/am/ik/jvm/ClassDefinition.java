package am.ik.jvm;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import am.ik.jvm.ConstantPool.ClassConstant;
import am.ik.jvm.ConstantPool.Utf8Constant;

import org.jspecify.annotations.Nullable;

/**
 * A class described as data before it is written: its constant pool, header, fields and
 * methods, each method body the instruction records its emitter left
 * ({@link MethodCode}), every constant-pool operand an entry of the master pool. The
 * shape is the one every generated class has -- attribute-free fields, one {@code Code}
 * attribute per method whose only sub-attribute is an optional {@code LineNumberTable},
 * no class attributes.
 * <p>
 * {@link JvmClassSplitter} writes it: as one class file when its members' entries fit one
 * pool, else spread over several. Keeping the class as data until that decision is what
 * makes the second outcome possible at all: a class's pool is decided only once its
 * members are.
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
	 * A field: attribute-free.
	 *
	 * @param access its access flags
	 * @param name its name
	 * @param descriptor its descriptor
	 */
	public record Field(int access, Utf8Constant name, Utf8Constant descriptor) {
	}

	/**
	 * A {@code LineNumberTable} entry: the instructions from {@code position} up to the
	 * next entry's belong to {@code lineNumber}. What the number MEANS is the producer's
	 * business -- the JVM only hands it back through
	 * {@link StackTraceElement#getLineNumber()}.
	 *
	 * @param position the position of the first instruction the entry covers
	 * ({@link MethodCode#position()})
	 * @param lineNumber the u2 number those instructions report
	 */
	public record Line(int position, int lineNumber) {
	}

	/**
	 * A method with its single {@code Code} attribute.
	 *
	 * @param access its access flags
	 * @param name its name
	 * @param descriptor its descriptor
	 * @param body the body, its every label bound
	 * @param lineNumbers the {@code LineNumberTable} entries, in ascending position
	 * order; empty for a method that carries none
	 */
	public record Method(int access, Utf8Constant name, Utf8Constant descriptor, MethodCode body,
			List<Line> lineNumbers) {

		/**
		 * Copies the line table.
		 */
		public Method {
			lineNumbers = List.copyOf(lineNumbers);
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
		 * Adds a method without line numbers.
		 * @param access the method's access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 * @param body the body, its every label bound
		 * @return this builder
		 * @throws IllegalStateException when a branch in the body waits for its label
		 */
		public Builder addMethod(int access, Utf8Constant name, Utf8Constant descriptor, MethodCode body) {
			return this.addMethod(access, name, descriptor, body, List.of());
		}

		/**
		 * @param access the method's access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 * @param body the body, its every label bound
		 * @param lineNumbers the {@code LineNumberTable} entries, in ascending position
		 * order (empty for none; any at all needs {@link #lineNumberTableName})
		 * @return this builder
		 * @throws IllegalStateException when a branch in the body waits for its label
		 */
		public Builder addMethod(int access, Utf8Constant name, Utf8Constant descriptor, MethodCode body,
				List<Line> lineNumbers) {
			body.checkComplete();
			this.methods.add(new Method(access, Objects.requireNonNull(name), Objects.requireNonNull(descriptor), body,
					lineNumbers));
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
