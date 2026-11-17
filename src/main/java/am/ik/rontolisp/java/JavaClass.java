package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaSyntax.ClassDeclaration;
import org.jspecify.annotations.Nullable;

/**
 * A class or interface of the program's class table: one the program declares, one of the
 * library written in Java and lowered with the program, or one of the library whose
 * values are Lisp values and whose members are forms ({@link JavaIntrinsics}).
 */
final class JavaClass {

	/** The text a class was read from, for the positions of what is said about it. */
	record Source(@Nullable String file, String text) {
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

		Field(JavaClass owner, String name, JavaType type, boolean isStatic, boolean isFinal,
				JavaSyntax.@Nullable Field declaration, @Nullable LispVal form) {
			this.owner = owner;
			this.name = name;
			this.type = type;
			this.isStatic = isStatic;
			this.isFinal = isFinal;
			this.declaration = declaration;
			this.form = form;
		}

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

	final String name;

	final boolean isInterface;

	final boolean isAbstract;

	/** Whether its values are Lisp values and its members forms. */
	final boolean intrinsic;

	final int typeParameters;

	final @Nullable ClassDeclaration declaration;

	final @Nullable Source source;

	@Nullable JavaClass superclass;

	final List<JavaClass> interfaces = new ArrayList<>();

	final List<Field> fields = new ArrayList<>();

	final List<Method> methods = new ArrayList<>();

	JavaClass(String name, boolean isInterface, boolean isAbstract, boolean intrinsic, int typeParameters,
			@Nullable ClassDeclaration declaration, @Nullable Source source) {
		this.name = name;
		this.isInterface = isInterface;
		this.isAbstract = isAbstract;
		this.intrinsic = intrinsic;
		this.typeParameters = typeParameters;
		this.declaration = declaration;
		this.source = source;
	}

	String simpleName() {
		return this.name.substring(this.name.lastIndexOf('.') + 1);
	}

	/**
	 * The name the class has in every Lisp name made of it: its qualified name, a
	 * {@code java.lang} class its simple one, as a program writes it.
	 */
	String lispName() {
		return this.name.startsWith("java.lang.") && this.name.indexOf('.', 10) < 0 ? simpleName() : this.name;
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

	/** How many classes it extends, the order a dispatch tests the classes in. */
	int depth() {
		return this.superclass == null ? 0 : 1 + this.superclass.depth();
	}

	@Override
	public String toString() {
		return this.name;
	}

}
