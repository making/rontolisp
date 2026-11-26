package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.List;

import am.ik.rontolisp.java.JavaClasses.Unit;
import am.ik.rontolisp.java.JavaSyntax.Import;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Wildcard;

/**
 * The Java source an annotation read where a program runs is made of. An annotation is an
 * instance of a class implementing its annotation type, a member of a class holding the
 * annotations of one class; the defaults of an annotation type are the static methods of
 * another. Both are written as Java, declared like a file of a library and lowered as far
 * as the program reaches them, so that an expression written for an element is lowered as
 * any expression of the file it stands in. A program reading no annotation has none of it
 * ({@link JavaClasses#annotationHolder}, {@link JavaClasses#annotationDefaults}).
 */
final class JavaAnnotations {

	private JavaAnnotations() {
	}

	/**
	 * What a generated file starts with: the package and the imports of the file it is
	 * made for, and the static members of the classes around the declaration, which a
	 * name written there may mean.
	 */
	static String header(Unit unit, List<String> enclosing) {
		StringBuilder text = new StringBuilder();
		if (unit.packageName() != null) {
			text.append("package ").append(unit.packageName()).append(";\n");
		}
		for (Import imported : unit.tree().imports()) {
			text.append("import ")
				.append(imported.module() ? "module " : imported.isStatic() ? "static " : "")
				.append(imported.name())
				.append(imported.onDemand() ? ".*" : "")
				.append(";\n");
		}
		for (String around : enclosing) {
			text.append("import static ").append(around).append(".*;\n");
		}
		return text.toString();
	}

	/**
	 * An element of an annotation as its class answers it.
	 *
	 * @param typeText its type as Java source names it from any file
	 * @param name its name
	 * @param answer the expression answering it
	 * @param type its type
	 */
	record Element(String typeText, String name, String answer, JavaType type) {
	}

	/** The class every helper of an annotation's members is a static method of. */
	private static final String HELPER = "rontolisp.internal.Annotations";

	/**
	 * A class an annotation is an instance of: it implements the annotation type, each
	 * element a method answering the expression given for it, so that an array is a new
	 * one each time it is asked for and an element nobody asks costs nothing; equal to
	 * another annotation of the type with equal elements, its hash code and its text as
	 * {@code java.lang.annotation.Annotation} specifies them and a JDK writes them. The
	 * text names the elements in the order a JDK's does ({@link #textOrder}); an
	 * annotation whose {@code value()} holds annotations answers them as a container of a
	 * repeatable type.
	 * @param simpleName the class's name
	 * @param type the annotation type's canonical name
	 * @param elements the elements, in the order declared
	 * @param order the elements' names in the order the text writes them
	 * @param full whether it is equal, hashed and written as a JDK's annotation; else an
	 * object's equality and hash code, its text refused, as a program holds where its own
	 * code reads no annotation ({@link JavaClasses#annotationsInFull})
	 */
	static String implementation(String simpleName, String type, List<Element> elements, List<String> order,
			boolean full) {
		StringBuilder members = new StringBuilder();
		StringBuilder same = new StringBuilder();
		StringBuilder hash = new StringBuilder();
		String contained = null;
		for (Element element : elements) {
			members.append("        public ")
				.append(element.typeText())
				.append(' ')
				.append(element.name())
				.append("() {\n            return ")
				.append(element.answer())
				.append(";\n        }\n");
			String mine = element.name() + "()";
			String theirs = "that." + element.name() + "()";
			boolean plain = element.type() instanceof JavaType.Primitive primitive
					&& primitive != JavaType.Primitive.FLOAT && primitive != JavaType.Primitive.DOUBLE;
			same.append(" && ").append(plain ? mine + " == " + theirs : HELPER + ".same(" + mine + ", " + theirs + ")");
			hash.append(hash.isEmpty() ? "" : " + ")
				.append('(')
				.append(127 * element.name().hashCode())
				.append(" ^ ")
				.append(HELPER)
				.append(".hash(")
				.append(mine)
				.append("))");
			if (element.name().equals("value") && element.type() instanceof ArrayType array
					&& array.element() instanceof ClassType named && named.declaration().isAnnotation) {
				contained = mine;
			}
		}
		StringBuilder text = new StringBuilder("\"@" + type + "(\"");
		for (int i = 0; i < order.size(); i++) {
			String name = order.get(i);
			boolean alone = order.size() == 1 && name.equals("value");
			text.append(" + \"")
				.append(i == 0 ? "" : ", ")
				.append(alone ? "" : name + "=")
				.append("\" + ")
				.append(HELPER)
				.append(".text(")
				.append(name)
				.append("())");
		}
		text.append(" + \")\"");
		if (!full) {
			// an object's equality and hash code, a text refused where the program runs
			return "    static final class " + simpleName + " implements " + type + " {\n" + members
					+ "        public Class<? extends java.lang.annotation.Annotation> annotationType() {\n"
					+ "            return " + type + ".class;\n        }\n"
					+ "        public String toString() {\n            throw new UnsupportedOperationException(\n"
					+ "                    \"toString() of an annotation is not supported by the experimental Java"
					+ " subset yet\");\n        }\n    }\n";
		}
		return "    static final class " + simpleName + " implements " + type
				+ (contained != null ? ", " + HELPER + ".Container" : "") + " {\n" + members
				+ "        public Class<? extends java.lang.annotation.Annotation> annotationType() {\n"
				+ "            return " + type + ".class;\n        }\n"
				+ "        public boolean equals(Object other) {\n            return other instanceof " + type + " that"
				+ same + ";\n        }\n" + "        public int hashCode() {\n            return "
				+ (hash.isEmpty() ? "0" : hash) + ";\n        }\n"
				+ "        public String toString() {\n            return " + text + ";\n        }\n"
				+ (contained != null
						? "        public java.lang.annotation.Annotation[] contained() {\n            return "
								+ contained + ";\n        }\n"
						: "")
				+ "    }\n";
	}

	/**
	 * The order a JDK's text of an annotation names its elements in (read off its answers
	 * to probes): those with a default first, in the order a hash map grown from no
	 * capacity iterates their names -- by the bucket of the name's hash code spread by
	 * its upper half, those of one bucket in the order put, which here is the order
	 * declared, where a JDK puts them in its own order of the type's methods --, then the
	 * elements with no default in the order the annotation writes them.
	 * @param defaulted the names of the elements with a default, in the order declared
	 * @param written the names of the elements with no default, in the order written
	 */
	static List<String> textOrder(List<String> defaulted, List<String> written) {
		int capacity = 2;
		while (defaulted.size() > capacity * 3 / 4) {
			capacity *= 2;
		}
		int mask = capacity - 1;
		List<String> order = new ArrayList<>(defaulted);
		order.sort(java.util.Comparator.comparingInt(name -> {
			int hash = name.hashCode();
			return (hash ^ (hash >>> 16)) & mask;
		}));
		order.addAll(written);
		return order;
	}

	/** A type as Java source names it from any file: its classes by their full names. */
	static String typeText(JavaType type) {
		return switch (type) {
			case ArrayType array -> typeText(array.element()) + "[]";
			case ClassType named -> {
				StringBuilder text = new StringBuilder(named.declaration().name);
				for (int i = 0; i < named.arguments().size(); i++) {
					text.append(i == 0 ? "<" : ", ").append(typeText(named.arguments().get(i)));
				}
				yield named.arguments().isEmpty() ? text.toString() : text.append('>').toString();
			}
			case Wildcard wildcard -> wildcard.upper() != null ? "? extends " + typeText(wildcard.upper())
					: wildcard.lower() != null ? "? super " + typeText(wildcard.lower()) : "?";
			default -> type.toString();
		};
	}

	/** A type with no type argument: what an array is made of. */
	static String erasedText(JavaType type) {
		return switch (type) {
			case ArrayType array -> erasedText(array.element()) + "[]";
			case ClassType named -> named.declaration().name;
			default -> type.toString();
		};
	}

}
