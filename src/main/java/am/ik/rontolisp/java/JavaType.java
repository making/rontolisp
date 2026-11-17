package am.ik.rontolisp.java;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A static type of the Java subset. The lowering picks every form from these: an
 * operator, a conversion and a call's target are decided while lowering, never tested
 * when the program runs.
 */
sealed interface JavaType {

	/** A primitive type, or {@code void}. */
	enum Primitive implements JavaType {

		BOOLEAN("boolean"), CHAR("char"), INT("int"), LONG("long"), DOUBLE("double"), VOID("void");

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
	 * @param arguments its type arguments: only a library collection has any
	 */
	record ClassType(JavaClass declaration, List<JavaType> arguments) implements JavaType {

		@Override
		public String toString() {
			return this.declaration.simpleName() + (this.arguments.isEmpty() ? ""
					: this.arguments.stream().map(JavaType::toString).collect(Collectors.joining(",", "<", ">")));
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
	 * A type parameter of a library class, in a member's signature: replaced by the
	 * receiver's type argument when the member is used.
	 *
	 * @param index which parameter
	 */
	record Variable(int index) implements JavaType {
	}

	default boolean isNumeric() {
		return this == Primitive.INT || this == Primitive.LONG || this == Primitive.DOUBLE || this == Primitive.CHAR;
	}

	default boolean isIntegral() {
		return this == Primitive.INT || this == Primitive.LONG || this == Primitive.CHAR;
	}

	default boolean isPrimitive() {
		return this instanceof Primitive && this != Primitive.VOID;
	}

	default boolean isReference() {
		return this instanceof ClassType || this instanceof ArrayType || this == Null.NULL;
	}

	/** The name the type has in a method's signature: its erasure. */
	default String erasure() {
		return switch (this) {
			case ClassType type -> type.declaration().lispName();
			case ArrayType type -> type.element().erasure() + "[]";
			case Variable variable -> "Object";
			default -> toString();
		};
	}

}
