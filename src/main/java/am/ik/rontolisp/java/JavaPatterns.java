package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

import am.ik.rontolisp.java.JavaType.ClassType;

/**
 * What a set of patterns covers: whether the cases of a switch take every value of its
 * selector's type, as the language asks of a switch expression and of a switch with a
 * pattern. A sealed type is covered when every type it permits is; a record, when its
 * record patterns cover its components together.
 */
final class JavaPatterns {

	private JavaPatterns() {
	}

	/** A pattern as far as covering goes. */
	sealed interface Shape {

	}

	/** A pattern taking every value: {@code var x}, the unnamed {@code _}. */
	record Any() implements Shape {
	}

	/** A type pattern. */
	record OfType(JavaType type) implements Shape {
	}

	/** A record pattern. */
	record OfRecord(JavaClass record, List<Shape> components) implements Shape {
	}

	/**
	 * Whether rows of patterns, one pattern a column, cover every tuple of values of the
	 * columns' types.
	 * @param rows the rows
	 * @param types the columns' types
	 * @param isSubtype whether a first type's values are a second's
	 * @return whether no tuple is left out
	 */
	static boolean covers(List<List<Shape>> rows, List<JavaType> types, BiPredicate<JavaType, JavaType> isSubtype) {
		if (types.isEmpty()) {
			return !rows.isEmpty();
		}
		if (rows.isEmpty()) {
			return false;
		}
		List<JavaType> rest = types.subList(1, types.size());
		for (JavaType leaf : leaves(types.get(0))) {
			List<List<Shape>> applicable = new ArrayList<>();
			JavaClass deconstructed = null;
			for (List<Shape> row : rows) {
				Shape head = row.get(0);
				if (head instanceof Any || (head instanceof OfType typed && isSubtype.test(leaf, typed.type()))) {
					applicable.add(row);
				}
				else if (head instanceof OfRecord record && leaf instanceof ClassType named
						&& named.declaration() == record.record()) {
					applicable.add(row);
					deconstructed = record.record();
				}
			}
			List<List<Shape>> narrowed = new ArrayList<>();
			List<JavaType> columns = new ArrayList<>();
			if (deconstructed != null) {
				for (JavaClass.Field component : deconstructed.fields) {
					if (!component.isStatic) {
						columns.add(component.type);
					}
				}
			}
			int width = columns.size();
			columns.addAll(rest);
			for (List<Shape> row : applicable) {
				List<Shape> next = new ArrayList<>();
				if (row.get(0) instanceof OfRecord record) {
					next.addAll(record.components());
				}
				else {
					for (int i = 0; i < width; i++) {
						next.add(new Any());
					}
				}
				next.addAll(row.subList(1, row.size()));
				narrowed.add(next);
			}
			if (!covers(narrowed, columns, isSubtype)) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The types a value of a type is one of: under a sealed type, the ones it permits.
	 */
	private static List<JavaType> leaves(JavaType type) {
		if (!(type instanceof ClassType named)) {
			return List.of(type);
		}
		List<JavaType> leaves = new ArrayList<>();
		leaves(named.declaration(), leaves, new HashSet<>());
		return leaves;
	}

	private static void leaves(JavaClass declaration, List<JavaType> into, Set<JavaClass> seen) {
		if (!seen.add(declaration)) {
			return;
		}
		if (!declaration.isSealed || declaration.isEnum) {
			into.add(new ClassType(declaration, List.of()));
			return;
		}
		if (!declaration.isAbstract) {
			into.add(new ClassType(declaration, List.of()));
		}
		for (JavaClass permitted : declaration.permits) {
			leaves(permitted, into, seen);
		}
	}

}
