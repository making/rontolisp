package am.ik.rontolisp.java;

import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

/**
 * A static type of the Java subset. The lowering picks every form from these: an
 * operator, a conversion and a call's target are decided while lowering, never tested
 * when the program runs.
 */
sealed interface JavaType {

	/** A primitive type, or {@code void}. */
	enum Primitive implements JavaType {

		BOOLEAN("boolean"), BYTE("byte"), SHORT("short"), CHAR("char"), INT("int"), LONG("long"), FLOAT("float"),
		DOUBLE("double"), VOID("void");

		private final String spelling;

		Primitive(String spelling) {
			this.spelling = spelling;
		}

		@Override
		public String toString() {
			return this.spelling;
		}

	}

	/** The type of {@code null}. */
	enum Null implements JavaType {

		NULL;

		@Override
		public String toString() {
			return "<null>";
		}

	}

	/**
	 * A class or interface type.
	 *
	 * @param declaration the class
	 * @param arguments its type arguments, one for each of the class's
	 * {@link JavaClass#allTypeParameters()}: its own, then those of the class around an
	 * inner member class; none for a class with none and for a raw type, whose members
	 * are read erased
	 */
	record ClassType(JavaClass declaration, List<JavaType> arguments) implements JavaType {

		@Override
		public String toString() {
			int own = this.declaration.typeParameters.size();
			JavaClass outer = this.declaration.outer;
			String prefix = "";
			List<JavaType> shown = this.arguments;
			if (outer != null && this.arguments.size() > own) {
				prefix = new ClassType(outer, this.arguments.subList(own, this.arguments.size())) + ".";
				shown = this.arguments.subList(0, own);
			}
			return prefix + this.declaration.simpleName() + (shown.isEmpty() ? ""
					: shown.stream().map(JavaType::toString).collect(Collectors.joining(",", "<", ">")));
		}

	}

	/**
	 * An array type.
	 *
	 * @param element its element type
	 */
	record ArrayType(JavaType element) implements JavaType {

		@Override
		public String toString() {
			return this.element + "[]";
		}

	}

	/**
	 * A type parameter as it is declared: by a class, by a method, or by nothing a
	 * program writes -- the capture of a wildcard, and a type argument an inference found
	 * nothing to say about, which its context then reads as the type it needs.
	 */
	final class TypeParameter {

		final String name;

		/** Its upper bounds; none is {@code Object}. Set once its declaration is read. */
		List<JavaType> bounds = List.of();

		/** What a capture of {@code ? super T} is above, or {@code null}. */
		@Nullable JavaType lower;

		/**
		 * The class declaring it, or {@code null}: a method's, a capture, an open one.
		 */
		@Nullable JavaClass owner;

		/** The method or constructor declaring it, or {@code null}. */
		JavaClass.@Nullable Method method;

		/** The wildcard it is the capture of, or {@code null}. */
		@Nullable Wildcard captured;

		/** Whether an inference left it open. */
		final boolean open;

		/** Whether a method declares it: an inference finds it at every call. */
		final boolean ofMethod;

		/**
		 * Whether it stands for an intersection type, {@code A & B}: a type under each of
		 * its bounds, which no declaration names.
		 */
		boolean intersection;

		TypeParameter(String name, boolean ofMethod, boolean open) {
			this.name = name;
			this.ofMethod = ofMethod;
			this.open = open;
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/**
	 * A type variable: a use of a type parameter. Two are one type when they are uses of
	 * one parameter.
	 *
	 * @param parameter the parameter
	 */
	record Variable(TypeParameter parameter) implements JavaType {

		@Override
		public String toString() {
			return this.parameter.name;
		}

	}

	/**
	 * A wildcard, which stands only as a type argument.
	 *
	 * @param upper what follows {@code extends}, or {@code null}
	 * @param lower what follows {@code super}, or {@code null}
	 */
	record Wildcard(@Nullable JavaType upper, @Nullable JavaType lower) implements JavaType {

		@Override
		public String toString() {
			return this.upper != null ? "? extends " + this.upper : this.lower != null ? "? super " + this.lower : "?";
		}

	}

	default boolean isNumeric() {
		return isIntegral() || isFloating();
	}

	default boolean isIntegral() {
		return this == Primitive.INT || this == Primitive.LONG || this == Primitive.CHAR || this == Primitive.BYTE
				|| this == Primitive.SHORT;
	}

	default boolean isFloating() {
		return this == Primitive.DOUBLE || this == Primitive.FLOAT;
	}

	/**
	 * Whether a value of the type is an {@code int} once an operator promotes it:
	 * {@code byte}, {@code short}, {@code char}.
	 */
	default boolean isBelowInt() {
		return this == Primitive.BYTE || this == Primitive.SHORT || this == Primitive.CHAR;
	}

	default boolean isPrimitive() {
		return this instanceof Primitive && this != Primitive.VOID;
	}

	default boolean isReference() {
		return this instanceof ClassType || this instanceof ArrayType || this instanceof Variable || this == Null.NULL;
	}

	/** The name the type has in a method's signature: its erasure. */
	default String erasure() {
		return switch (this) {
			case ClassType type -> type.declaration().lispName();
			case ArrayType type -> type.element().erasure() + "[]";
			case Variable variable ->
				variable.parameter().bounds.isEmpty() ? "Object" : variable.parameter().bounds.get(0).erasure();
			default -> toString();
		};
	}

}
