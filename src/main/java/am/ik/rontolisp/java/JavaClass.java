package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaSyntax.ClassDeclaration;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.TypeParameter;
import org.jspecify.annotations.Nullable;

/**
 * A class or interface of the program's class table: one the program declares, one of the
 * library written in Java and lowered with the program, or one of the library whose
 * values are Lisp values and whose members are forms ({@link JavaIntrinsics}).
 */
final class JavaClass {

	/**
	 * The text a class was read from, for the positions of what is said about it.
	 *
	 * @param file the file, or {@code null}
	 * @param text its text
	 * @param library whether it is Java source of the library, lowered as far as the
	 * program reaches it, and no file of the program, lowered whole
	 */
	record Source(@Nullable String file, String text, boolean library) {
	}

	/** A field. */
	static final class Field {

		final JavaClass owner;

		final String name;

		final JavaType type;

		final boolean isStatic;

		final boolean isFinal;

		final JavaSyntax.@Nullable Field declaration;

		/** An intrinsic field's value, or {@code null} for a declared field. */
		final @Nullable LispVal form;

		/** What a field the front end adds is initialized with, or {@code null}. */
		JavaSyntax.@Nullable Expression initializer;

		/** Where it is declared: an enum constant's or a record component's name. */
		int pos;

		boolean isPrivate;

		/** Whether it is an enum constant. */
		boolean isConstant;

		/**
		 * Whether nothing can change what it holds once it is set: the array an enum's
		 * {@code values()} copies.
		 */
		boolean frozen;

		Field(JavaClass owner, String name, JavaType type, boolean isStatic, boolean isFinal,
				JavaSyntax.@Nullable Field declaration, @Nullable LispVal form) {
			this.owner = owner;
			this.name = name;
			this.type = type;
			this.isStatic = isStatic;
			this.isFinal = isFinal;
			this.declaration = declaration;
			this.form = form;
			this.initializer = declaration == null ? null : declaration.initializer();
			this.pos = declaration == null ? 0 : declaration.pos();
			this.isPrivate = declaration != null && declaration.modifiers().contains("private");
		}

	}

	/**
	 * A local variable of an enclosing method that a local or anonymous class reads: a
	 * slot of the class, set where an instance is made.
	 *
	 * @param name the variable
	 * @param type its type
	 * @param slot the slot's name, the class's own
	 */
	record Capture(String name, JavaType type, String slot) {
	}

	/** A method or a constructor. */
	static final class Method {

		final JavaClass owner;

		final String name;

		final List<JavaType> parameters;

		final JavaType returnType;

		final boolean isStatic;

		final boolean isAbstract;

		final JavaSyntax.@Nullable Method declaration;

		/** An intrinsic member's lowering, or {@code null} for a declared method. */
		final @Nullable JavaEmitter emitter;

		/** Whether an intrinsic member takes its arguments as they are typed. */
		final boolean raw;

		/** Whether its last parameter takes the arguments past the ones before it. */
		boolean varargs;

		boolean isPrivate;

		/**
		 * How many parameters the front end added ahead of the declared ones: an enum
		 * constructor's name and ordinal.
		 */
		int hidden;

		/**
		 * The constructor an anonymous class's own hands its arguments to, or
		 * {@code null}.
		 */
		@Nullable Method passesTo;

		/** The type parameters it declares: found at every call. */
		List<TypeParameter> typeParameters = List.of();

		Method(JavaClass owner, String name, List<JavaType> parameters, JavaType returnType, boolean isStatic,
				boolean isAbstract, JavaSyntax.@Nullable Method declaration, @Nullable JavaEmitter emitter,
				boolean raw) {
			this.owner = owner;
			this.name = name;
			this.parameters = parameters;
			this.returnType = returnType;
			this.isStatic = isStatic;
			this.isAbstract = isAbstract;
			this.declaration = declaration;
			this.emitter = emitter;
			this.raw = raw;
			this.varargs = declaration != null && declaration.varargs();
			this.isPrivate = declaration != null && declaration.modifiers().contains("private");
		}

		/**
		 * Whether it is an interface's method with a body an implementing class inherits.
		 */
		boolean isDefault() {
			return this.owner.isInterface && !this.isAbstract && !this.isStatic && !this.isPrivate;
		}

		boolean isConstructor() {
			return this.name.equals("<init>");
		}

		/** Whether it is one of {@code Object}'s methods, which every class has. */
		boolean isObjectMethod() {
			String signature = signature();
			return signature.equals("toString()") || signature.equals("equals(Object)")
					|| signature.equals("hashCode()");
		}

		/** The name and the erased parameter types: what an override shares. */
		String signature() {
			StringBuilder signature = new StringBuilder(this.name).append('(');
			for (int i = 0; i < this.parameters.size(); i++) {
				signature.append(i > 0 ? "," : "").append(this.parameters.get(i).erasure());
			}
			return signature.append(')').toString();
		}

		/** The parameter list alone, for the names of a constructor's two functions. */
		String parameterList() {
			return signature().substring(this.name.length());
		}

	}

	/**
	 * The name it is found by: its canonical name, {@code pkg.Outer.Inner}; one of its
	 * own for a local or an anonymous class, which no program can write.
	 */
	final String name;

	/** What {@code getClass().getName()} answers: {@code pkg.Outer$Inner}. */
	final String binaryName;

	/** Its simple name, empty for an anonymous class. */
	final String simple;

	final boolean isInterface;

	boolean isAbstract;

	boolean isEnum;

	boolean isRecord;

	boolean isFinal;

	/** Whether it is {@code sealed}: its direct subtypes are {@link #permits}. */
	boolean isSealed;

	/** Whether its values are Lisp values and its members forms. */
	final boolean intrinsic;

	/** The type parameters it declares. */
	final List<TypeParameter> typeParameters = new ArrayList<>();

	final @Nullable ClassDeclaration declaration;

	final @Nullable Source source;

	/** The class its declaration stands in, or {@code null} for a top-level class. */
	@Nullable JavaClass outer;

	/**
	 * Whether an instance holds no instance of {@link #outer}: a top-level class, a
	 * static member, an interface, an enum, a record, a class declared in a static
	 * context.
	 */
	boolean isStatic = true;

	/** Whether it is a local or an anonymous class: lowered where it is declared. */
	boolean isLocal;

	/** The type parameters of the method a local class is declared in. */
	List<TypeParameter> scope = List.of();

	@Nullable JavaClass superclass;

	final List<JavaClass> interfaces = new ArrayList<>();

	/**
	 * What it extends and implements as written, type arguments and all, by class: a
	 * supertype that is not here is one with no argument.
	 */
	final Map<JavaClass, ClassType> parameterized = new LinkedHashMap<>();

	final List<JavaClass> permits = new ArrayList<>();

	/** Its member types, by simple name. */
	final Map<String, JavaClass> members = new LinkedHashMap<>();

	final List<Field> fields = new ArrayList<>();

	final List<Method> methods = new ArrayList<>();

	/** The locals of the enclosing methods a local class reads, by name. */
	final Map<String, Capture> captures = new LinkedHashMap<>();

	JavaClass(String name, boolean isInterface, boolean isAbstract, boolean intrinsic,
			@Nullable ClassDeclaration declaration, @Nullable Source source) {
		this(name, name, name.substring(name.lastIndexOf('.') + 1), isInterface, isAbstract, intrinsic, declaration,
				source);
	}

	JavaClass(String name, String binaryName, String simple, boolean isInterface, boolean isAbstract, boolean intrinsic,
			@Nullable ClassDeclaration declaration, @Nullable Source source) {
		this.name = name;
		this.binaryName = binaryName;
		this.simple = simple;
		this.isInterface = isInterface;
		this.isAbstract = isAbstract;
		this.intrinsic = intrinsic;
		this.declaration = declaration;
		this.source = source;
	}

	/** Whether an instance holds the instance of {@link #outer} it was made in. */
	boolean hasOuterInstance() {
		return this.outer != null && !this.isStatic;
	}

	/** The top-level class its declaration stands in: the unit of private access. */
	JavaClass topLevel() {
		JavaClass top = this;
		while (top.outer != null) {
			top = top.outer;
		}
		return top;
	}

	String simpleName() {
		return this.simple;
	}

	/**
	 * The name the class has in every Lisp name made of it: its qualified name, a
	 * {@code java.lang} class its simple one, as a program writes it.
	 */
	String lispName() {
		return this.name.startsWith("java.lang.") && this.name.indexOf('.', 10) < 0 ? this.simple : this.name;
	}

	boolean isSubclassOf(JavaClass other) {
		if (this == other) {
			return true;
		}
		if (this.superclass != null && this.superclass.isSubclassOf(other)) {
			return true;
		}
		for (JavaClass implemented : this.interfaces) {
			if (implemented.isSubclassOf(other)) {
				return true;
			}
		}
		return false;
	}

	/** A direct supertype as the class's declaration writes it. */
	ClassType supertype(JavaClass above) {
		ClassType written = this.parameterized.get(above);
		return written != null ? written : new ClassType(above, List.of());
	}

	/** The class as a type inside its own body: its parameters are its arguments. */
	ClassType self() {
		List<JavaType> arguments = new ArrayList<>();
		for (TypeParameter parameter : this.typeParameters) {
			arguments.add(new JavaType.Variable(parameter));
		}
		return new ClassType(this, List.copyOf(arguments));
	}

	/** How many classes it extends, the order a dispatch tests the classes in. */
	int depth() {
		return this.superclass == null ? 0 : 1 + this.superclass.depth();
	}

	@Override
	public String toString() {
		return this.name;
	}

}
