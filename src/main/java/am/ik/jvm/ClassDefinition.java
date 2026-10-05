package am.ik.jvm;

import java.lang.classfile.constantpool.ClassEntry;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

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

	private final ClassEntry thisClass;

	private final ClassEntry superClass;

	private final Utf8Entry codeName;

	private final @Nullable Utf8Entry lineNumberTableName;

	private final List<ClassEntry> interfaces;

	private final List<Field> fields;

	private final List<Method> methods;

	private ClassDefinition(Builder builder) {
		this(builder.cp, builder.accessFlags, builder.thisClass, builder.superClass, builder.codeName,
				builder.lineNumberTableName, builder.interfaces, builder.fields, builder.methods);
	}

	private ClassDefinition(ConstantPool cp, int accessFlags, ClassEntry thisClass, ClassEntry superClass,
			Utf8Entry codeName, @Nullable Utf8Entry lineNumberTableName, List<ClassEntry> interfaces,
			List<Field> fields, List<Method> methods) {
		this.cp = cp;
		this.accessFlags = accessFlags;
		this.thisClass = thisClass;
		this.superClass = superClass;
		this.codeName = codeName;
		this.lineNumberTableName = lineNumberTableName;
		this.interfaces = List.copyOf(interfaces);
		this.fields = List.copyOf(fields);
		this.methods = List.copyOf(methods);
		if (this.lineNumberTableName == null) {
			for (Method method : this.methods) {
				if (!method.lineNumbers().isEmpty()) {
					throw new IllegalStateException("method " + method.name().stringValue()
							+ " carries line numbers, but the definition names no LineNumberTable attribute");
				}
			}
		}
	}

	/**
	 * Starts a definition.
	 * @param cp the master pool every entry in the definition belongs to
	 * @param accessFlags the class's access flags
	 * @param thisClass the class itself
	 * @param superClass its superclass
	 * @param codeName the {@code "Code"} Utf8 every method's attribute is named by
	 * @return a builder
	 */
	public static Builder builder(ConstantPool cp, int accessFlags, ClassEntry thisClass, ClassEntry superClass,
			Utf8Entry codeName) {
		return new Builder(cp, accessFlags, thisClass, superClass, codeName);
	}

	/**
	 * @return the master pool every entry in the definition belongs to
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
	public ClassEntry thisClass() {
		return this.thisClass;
	}

	/**
	 * @return its superclass
	 */
	public ClassEntry superClass() {
		return this.superClass;
	}

	/**
	 * @return the {@code "Code"} Utf8
	 */
	public Utf8Entry codeName() {
		return this.codeName;
	}

	/**
	 * @return the {@code "LineNumberTable"} Utf8 every method's line numbers are named
	 * by, or {@code null} when no method carries any
	 */
	public @Nullable Utf8Entry lineNumberTableName() {
		return this.lineNumberTableName;
	}

	/**
	 * @return the implemented interfaces, in declaration order
	 */
	public List<ClassEntry> interfaces() {
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
	 * The same class with other methods: a generator that decides some bodies by what the
	 * rest reach ({@link JvmClassSplitter#reach}) swaps them in once it has asked.
	 * @param methods the methods, in declaration order, their every label bound
	 * @return the definition
	 * @throws IllegalStateException when a branch in a body waits for its label
	 */
	public ClassDefinition withMethods(List<Method> methods) {
		for (Method method : methods) {
			method.body().checkComplete();
		}
		return new ClassDefinition(this.cp, this.accessFlags, this.thisClass, this.superClass, this.codeName,
				this.lineNumberTableName, this.interfaces, this.fields, methods);
	}

	/**
	 * A field: attribute-free.
	 *
	 * @param access its access flags
	 * @param name its name
	 * @param descriptor its descriptor
	 */
	public record Field(int access, Utf8Entry name, Utf8Entry descriptor) {
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
	public record Method(int access, Utf8Entry name, Utf8Entry descriptor, MethodCode body, List<Line> lineNumbers) {

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

		private final ClassEntry thisClass;

		private final ClassEntry superClass;

		private final Utf8Entry codeName;

		private @Nullable Utf8Entry lineNumberTableName;

		private final List<ClassEntry> interfaces = new ArrayList<>();

		private final List<Field> fields = new ArrayList<>();

		private final List<Method> methods = new ArrayList<>();

		private Builder(ConstantPool cp, int accessFlags, ClassEntry thisClass, ClassEntry superClass,
				Utf8Entry codeName) {
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
		public Builder lineNumberTableName(Utf8Entry name) {
			this.lineNumberTableName = Objects.requireNonNull(name);
			return this;
		}

		/**
		 * @param iface an implemented interface
		 * @return this builder
		 */
		public Builder addInterface(ClassEntry iface) {
			this.interfaces.add(Objects.requireNonNull(iface));
			return this;
		}

		/**
		 * @param access the field's access flags
		 * @param name its name
		 * @param descriptor its descriptor
		 * @return this builder
		 */
		public Builder addField(int access, Utf8Entry name, Utf8Entry descriptor) {
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
		public Builder addMethod(int access, Utf8Entry name, Utf8Entry descriptor, MethodCode body) {
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
		public Builder addMethod(int access, Utf8Entry name, Utf8Entry descriptor, MethodCode body,
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
