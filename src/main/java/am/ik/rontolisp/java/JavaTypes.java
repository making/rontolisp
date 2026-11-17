package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.java.JavaType.TypeParameter;
import am.ik.rontolisp.java.JavaType.Variable;
import am.ik.rontolisp.java.JavaType.Wildcard;
import org.jspecify.annotations.Nullable;

/**
 * The relations between the static types of the Java subset: what a parameterized type's
 * member is through it, which type is under which, what an inference finds for a type
 * parameter. Types are erased where the program runs, so nothing here picks a form but
 * through the conversions the lowering makes of the answers: a boxing, an unboxing, a
 * refusal.
 *
 * <p>
 * Written from the language specification's rules, narrowed where the subset needs less:
 * <ul>
 * <li>A wildcard is captured where a member is read through it and projected back to its
 * bound in the member's type.</li>
 * <li>A raw type's members are read erased, and a raw type converts to and from any
 * parameterization of its class.</li>
 * <li>A type argument an inference found nothing to say about is left OPEN: a variable
 * every type converts to and from, which its context reads as the type it needs. A call
 * that is an argument of another leaves open, above the type it found, what only the
 * values given for it say: the call around it may need a type above. Both stand for the
 * inference a compiler finishes from the target type.</li>
 * </ul>
 */
final class JavaTypes {

	private final JavaClasses classes;

	final ClassType object;

	private int captures;

	JavaTypes(JavaClasses classes) {
		this.classes = classes;
		this.object = classes.libraryType("java.lang.Object");
	}

	// ---- substitution

	static JavaType substitute(JavaType type, Map<TypeParameter, JavaType> bindings) {
		if (bindings.isEmpty()) {
			return type;
		}
		return switch (type) {
			case Variable variable -> {
				JavaType bound = bindings.get(variable.parameter());
				yield bound != null ? bound : type;
			}
			case ClassType named -> {
				if (named.arguments().isEmpty()) {
					yield type;
				}
				List<JavaType> arguments = new ArrayList<>();
				for (JavaType argument : named.arguments()) {
					arguments.add(substitute(argument, bindings));
				}
				yield arguments.equals(named.arguments()) ? type : new ClassType(named.declaration(), arguments);
			}
			case ArrayType array -> {
				JavaType element = substitute(array.element(), bindings);
				yield element == array.element() ? type : new ArrayType(element);
			}
			case Wildcard wildcard ->
				new Wildcard(wildcard.upper() == null ? null : substitute(wildcard.upper(), bindings),
						wildcard.lower() == null ? null : substitute(wildcard.lower(), bindings));
			default -> type;
		};
	}

	/** Whether a type names one of some type parameters. */
	static boolean mentions(JavaType type, Set<TypeParameter> parameters) {
		return switch (type) {
			case Variable variable -> parameters.contains(variable.parameter());
			case ClassType named -> named.arguments().stream().anyMatch(argument -> mentions(argument, parameters));
			case ArrayType array -> mentions(array.element(), parameters);
			case Wildcard wildcard -> (wildcard.upper() != null && mentions(wildcard.upper(), parameters))
					|| (wildcard.lower() != null && mentions(wildcard.lower(), parameters));
			default -> false;
		};
	}

	/** Whether a parameterized class is named with no argument. */
	static boolean isRaw(ClassType type) {
		return type.arguments().isEmpty() && !type.declaration().typeParameters.isEmpty();
	}

	/** A type as the program that runs has it: no argument, a variable its bound. */
	JavaType erased(JavaType type) {
		return switch (type) {
			case Variable variable ->
				variable.parameter().bounds.isEmpty() ? this.object : erased(variable.parameter().bounds.get(0));
			case ClassType named -> named.arguments().isEmpty() ? type : new ClassType(named.declaration(), List.of());
			case ArrayType array -> new ArrayType(erased(array.element()));
			case Wildcard wildcard -> wildcard.upper() == null ? this.object : erased(wildcard.upper());
			default -> type;
		};
	}

	/**
	 * What a parameterized type gives each parameter of its class; the erasure of the
	 * parameter's bound for a raw type.
	 */
	Map<TypeParameter, JavaType> bindings(ClassType type) {
		List<TypeParameter> parameters = type.declaration().typeParameters;
		if (parameters.isEmpty()) {
			return Map.of();
		}
		Map<TypeParameter, JavaType> bindings = new HashMap<>();
		for (int i = 0; i < parameters.size(); i++) {
			bindings.put(parameters.get(i),
					type.arguments().isEmpty() ? erased(new Variable(parameters.get(i))) : type.arguments().get(i));
		}
		return bindings;
	}

	// ---- supertypes

	/**
	 * The supertype of a type that is of a class, with the arguments the type gives it,
	 * or {@code null} when the type is not under the class.
	 */
	@Nullable ClassType asSuper(JavaType type, JavaClass above) {
		switch (type) {
			case ClassType named: {
				JavaClass declaration = named.declaration();
				if (declaration == above) {
					return named;
				}
				if (!declaration.isSubclassOf(above)) {
					return above == this.object.declaration() ? this.object : null;
				}
				boolean raw = isRaw(named);
				Map<TypeParameter, JavaType> bindings = raw ? Map.of() : bindings(named);
				List<JavaClass> direct = new ArrayList<>();
				if (declaration.superclass != null) {
					direct.add(declaration.superclass);
				}
				direct.addAll(declaration.interfaces);
				for (JavaClass parent : direct) {
					ClassType seen = raw ? new ClassType(parent, List.of())
							: (ClassType) substitute(declaration.supertype(parent), bindings);
					ClassType found = asSuper(seen, above);
					if (found != null) {
						return found;
					}
				}
				return null;
			}
			case Variable variable: {
				for (JavaType bound : variable.parameter().bounds) {
					ClassType found = asSuper(bound, above);
					if (found != null) {
						return found;
					}
				}
				return above == this.object.declaration() ? this.object : null;
			}
			case ArrayType array:
				return above == this.object.declaration() ? this.object : null;
			default:
				return null;
		}
	}

	/**
	 * The type a member has through a type it is read through: the parameters of the
	 * class declaring it replaced by what that type gives them, erased through a raw
	 * type.
	 * @param declared the member's type as its class declares it
	 * @param site the type it is read through, or {@code null} inside the class itself
	 * @param owner the class declaring it
	 */
	JavaType memberType(JavaType declared, @Nullable JavaType site, JavaClass owner) {
		if (site == null || owner.typeParameters.isEmpty()) {
			return declared;
		}
		ClassType above = asSuper(site, owner);
		if (above == null) {
			return declared;
		}
		return above.arguments().isEmpty() ? erased(declared) : substitute(declared, bindings(above));
	}

	/**
	 * The class type a member is looked for in: the type itself with its wildcards
	 * captured, a variable's bound, {@code Object} for an array.
	 */
	ClassType site(JavaType type) {
		return switch (type) {
			case ClassType named -> capture(named);
			case Variable variable ->
				variable.parameter().bounds.isEmpty() ? this.object : site(variable.parameter().bounds.get(0));
			default -> this.object;
		};
	}

	/**
	 * A type whose wildcards are each a type of its own: a fresh parameter bounded as the
	 * wildcard is.
	 */
	ClassType capture(ClassType type) {
		if (type.arguments().stream().noneMatch(Wildcard.class::isInstance)) {
			return type;
		}
		List<TypeParameter> declared = type.declaration().typeParameters;
		List<JavaType> arguments = new ArrayList<>();
		Map<TypeParameter, JavaType> bindings = new HashMap<>();
		List<TypeParameter> captured = new ArrayList<>();
		for (int i = 0; i < type.arguments().size(); i++) {
			JavaType argument = type.arguments().get(i);
			if (argument instanceof Wildcard wildcard) {
				TypeParameter capture = new TypeParameter("capture#" + (++this.captures) + " of " + wildcard, false,
						false);
				capture.captured = wildcard;
				capture.lower = wildcard.lower();
				captured.add(capture);
				argument = new Variable(capture);
			}
			else {
				captured.add(null);
			}
			arguments.add(argument);
			bindings.put(declared.get(i), argument);
		}
		for (int i = 0; i < captured.size(); i++) {
			TypeParameter capture = captured.get(i);
			if (capture == null) {
				continue;
			}
			Wildcard wildcard = java.util.Objects.requireNonNull(capture.captured);
			List<JavaType> bounds = new ArrayList<>();
			if (wildcard.upper() != null) {
				bounds.add(wildcard.upper());
			}
			for (JavaType bound : declared.get(i).bounds) {
				bounds.add(substitute(bound, bindings));
			}
			capture.bounds = List.copyOf(bounds);
		}
		return new ClassType(type.declaration(), List.copyOf(arguments));
	}

	/**
	 * A type with no captured wildcard left in it: a capture is its bound, and the
	 * wildcard itself where it is a type argument.
	 */
	JavaType upward(JavaType type) {
		return upward(type, 0);
	}

	private JavaType upward(JavaType type, int depth) {
		return switch (type) {
			case Variable variable when variable.parameter().captured != null -> {
				List<JavaType> bounds = variable.parameter().bounds;
				yield bounds.isEmpty() || depth > 4 ? this.object : upward(bounds.get(0), depth + 1);
			}
			case ClassType named -> {
				if (named.arguments().isEmpty()) {
					yield type;
				}
				List<JavaType> arguments = new ArrayList<>();
				for (JavaType argument : named.arguments()) {
					if (argument instanceof Variable variable && variable.parameter().captured != null) {
						arguments.add(variable.parameter().captured);
					}
					else {
						arguments.add(depth > 4 ? argument : upward(argument, depth + 1));
					}
				}
				yield arguments.equals(named.arguments()) ? type : new ClassType(named.declaration(), arguments);
			}
			case ArrayType array -> {
				JavaType element = upward(array.element(), depth);
				yield element == array.element() ? type : new ArrayType(element);
			}
			default -> type;
		};
	}

	/**
	 * A type with what an inference left open read as the type it found, its bound when
	 * it found none.
	 */
	JavaType ground(JavaType type) {
		return switch (type) {
			case Variable variable when variable.parameter().open -> variable.parameter().lower != null
					? ground(variable.parameter().lower)
					: variable.parameter().bounds.isEmpty() ? this.object : ground(variable.parameter().bounds.get(0));
			case ClassType named -> {
				if (named.arguments().isEmpty()) {
					yield type;
				}
				List<JavaType> arguments = new ArrayList<>();
				for (JavaType argument : named.arguments()) {
					arguments.add(ground(argument));
				}
				yield arguments.equals(named.arguments()) ? type : new ClassType(named.declaration(), arguments);
			}
			case ArrayType array -> {
				JavaType element = ground(array.element());
				yield element == array.element() ? type : new ArrayType(element);
			}
			default -> type;
		};
	}

	/**
	 * A variable an inference leaves open, bounded as the parameter it stands for.
	 * @param found the type the inference found from the values given for it, which the
	 * variable is or is above, or {@code null}
	 */
	Variable open(TypeParameter parameter, Map<TypeParameter, JavaType> bindings, @Nullable JavaType found) {
		TypeParameter open = new TypeParameter(found != null ? found.toString()
				: parameter.bounds.isEmpty() ? "Object" : parameter.bounds.get(0).toString(), false, true);
		List<JavaType> bounds = new ArrayList<>();
		for (JavaType bound : parameter.bounds) {
			bounds.add(substitute(bound, bindings));
		}
		open.bounds = List.copyOf(bounds);
		open.lower = found;
		return new Variable(open);
	}

	private static boolean isOpen(JavaType type) {
		return type instanceof Variable variable && variable.parameter().open;
	}

	/** Whether a type is one an open variable can be: any, or one above what it found. */
	private boolean takes(JavaType open, JavaType type) {
		JavaType found = ((Variable) open).parameter().lower;
		return found == null || isOpen(type) || isSubtype(found, type);
	}

	// ---- subtyping

	/** Whether two types are one, an open variable being any type. */
	boolean sameType(@Nullable JavaType a, @Nullable JavaType b) {
		if (a == null || b == null) {
			return a == b;
		}
		if (a.equals(b)) {
			return true;
		}
		if (isOpen(a)) {
			return takes(a, b);
		}
		if (isOpen(b)) {
			return takes(b, a);
		}
		if (a instanceof ClassType left && b instanceof ClassType right) {
			if (left.declaration() != right.declaration() || left.arguments().size() != right.arguments().size()) {
				return false;
			}
			for (int i = 0; i < left.arguments().size(); i++) {
				if (!sameType(left.arguments().get(i), right.arguments().get(i))) {
					return false;
				}
			}
			return true;
		}
		if (a instanceof ArrayType left && b instanceof ArrayType right) {
			return sameType(left.element(), right.element());
		}
		if (a instanceof Wildcard left && b instanceof Wildcard right) {
			return sameType(left.upper(), right.upper()) && sameType(left.lower(), right.lower());
		}
		return false;
	}

	/**
	 * Whether a value of one type is a value of another with no conversion but the
	 * unchecked one a raw type allows.
	 */
	boolean isSubtype(JavaType from, JavaType to) {
		if (sameType(from, to)) {
			return true;
		}
		if (!from.isReference() || !to.isReference() || to == JavaType.Null.NULL) {
			return false;
		}
		if (from == JavaType.Null.NULL) {
			return true;
		}
		if (to instanceof Variable variable) {
			TypeParameter parameter = variable.parameter();
			if (parameter.lower != null && isSubtype(from, parameter.lower)) {
				return true;
			}
			if (!(from instanceof Variable)) {
				return false;
			}
		}
		if (from instanceof Variable variable) {
			for (JavaType bound : variable.parameter().bounds) {
				if (isSubtype(bound, to)) {
					return true;
				}
			}
			return to.equals(this.object);
		}
		if (to.equals(this.object)) {
			return true;
		}
		if (from instanceof ClassType source && to instanceof ClassType target) {
			ClassType above = asSuper(source, target.declaration());
			if (above == null) {
				return false;
			}
			if (above.arguments().isEmpty() || target.arguments().isEmpty()) {
				return true;
			}
			for (int i = 0; i < target.arguments().size(); i++) {
				if (!contains(target.arguments().get(i), above.arguments().get(i))) {
					return false;
				}
			}
			return true;
		}
		if (from instanceof ArrayType source && to instanceof ArrayType target) {
			return source.element().isReference() && target.element().isReference()
					&& isSubtype(source.element(), target.element());
		}
		return false;
	}

	/** Whether a type argument takes another: a wildcard what is inside its bound. */
	private boolean contains(JavaType argument, JavaType given) {
		if (!(argument instanceof Wildcard wildcard)) {
			return sameType(argument, given);
		}
		if (wildcard.upper() != null) {
			JavaType upper = given instanceof Wildcard inner ? (inner.upper() != null ? inner.upper() : this.object)
					: given;
			if (!isSubtype(upper, wildcard.upper())) {
				return false;
			}
		}
		if (wildcard.lower() != null) {
			if (given instanceof Wildcard inner) {
				return inner.lower() != null && isSubtype(wildcard.lower(), inner.lower());
			}
			return isSubtype(wildcard.lower(), given);
		}
		return true;
	}

	private static void supertypes(JavaClass declaration, Set<JavaClass> into) {
		if (!into.add(declaration)) {
			return;
		}
		if (declaration.superclass != null) {
			supertypes(declaration.superclass, into);
		}
		for (JavaClass implemented : declaration.interfaces) {
			supertypes(implemented, into);
		}
	}

	/**
	 * The type two reference types share: the one that is above the other, else the one
	 * class or interface both are under and no other of theirs is under, else
	 * {@code Object}.
	 */
	JavaType lub(JavaType a, JavaType b) {
		if (isOpen(a) && ((Variable) a).parameter().lower != null) {
			return lub(java.util.Objects.requireNonNull(((Variable) a).parameter().lower), b);
		}
		if (isOpen(b) && ((Variable) b).parameter().lower != null) {
			return lub(a, java.util.Objects.requireNonNull(((Variable) b).parameter().lower));
		}
		if (a == JavaType.Null.NULL || isOpen(a)) {
			return b;
		}
		if (b == JavaType.Null.NULL || isOpen(b)) {
			return a;
		}
		if (isSubtype(a, b)) {
			return b;
		}
		if (isSubtype(b, a)) {
			return a;
		}
		if (a instanceof Variable variable) {
			return lub(site(variable), b);
		}
		if (b instanceof Variable variable) {
			return lub(a, site(variable));
		}
		if (!(a instanceof ClassType left) || !(b instanceof ClassType right)) {
			return this.object;
		}
		Set<JavaClass> above = new LinkedHashSet<>();
		supertypes(left.declaration(), above);
		Set<JavaClass> shared = new LinkedHashSet<>();
		supertypes(right.declaration(), shared);
		shared.retainAll(above);
		shared.remove(this.object.declaration());
		List<JavaClass> least = shared.stream()
			.filter(type -> shared.stream().noneMatch(other -> other != type && other.isSubclassOf(type)))
			.toList();
		List<JavaClass> concrete = least.stream().filter(type -> !type.isInterface).toList();
		JavaClass chosen = concrete.size() == 1 ? concrete.get(0) : least.size() == 1 ? least.get(0) : null;
		if (chosen == null) {
			return this.object;
		}
		ClassType mine = java.util.Objects.requireNonNull(asSuper(left, chosen));
		ClassType theirs = java.util.Objects.requireNonNull(asSuper(right, chosen));
		if (mine.arguments().isEmpty() || theirs.arguments().isEmpty()) {
			return new ClassType(chosen, List.of());
		}
		List<JavaType> arguments = new ArrayList<>();
		for (int i = 0; i < mine.arguments().size(); i++) {
			arguments.add(sameType(mine.arguments().get(i), theirs.arguments().get(i)) ? mine.arguments().get(i)
					: new Wildcard(null, null));
		}
		return new ClassType(chosen, List.copyOf(arguments));
	}

	/**
	 * The type a value of a static type has when it is an instance of a class: the class
	 * with the arguments the static type settles, a wildcard for each it does not. What a
	 * pattern naming a parameterized class with no argument binds, and what a sealed
	 * type's permitted class is under one of its parameterizations.
	 */
	ClassType refine(JavaClass declaration, JavaType from) {
		if (declaration.typeParameters.isEmpty()) {
			return new ClassType(declaration, List.of());
		}
		ClassType self = declaration.self();
		Inference inference = new Inference(declaration.typeParameters);
		JavaType source = from instanceof Variable variable ? site(variable) : from;
		if (source instanceof ClassType named && !named.arguments().isEmpty()) {
			ClassType above = asSuper(self, named.declaration());
			if (above != null && above.arguments().size() == named.arguments().size()) {
				for (int i = 0; i < named.arguments().size(); i++) {
					if (!(named.arguments().get(i) instanceof Wildcard)) {
						inference.same(above.arguments().get(i), named.arguments().get(i));
					}
				}
			}
		}
		List<JavaType> arguments = new ArrayList<>();
		for (TypeParameter parameter : declaration.typeParameters) {
			JavaType found = inference.resolved(parameter);
			arguments.add(found != null ? found : new Wildcard(null, null));
		}
		return new ClassType(declaration, List.copyOf(arguments));
	}

	// ---- inference

	/**
	 * What the arguments of one call, and the type its value is wanted as, say about the
	 * type parameters the call has to find: the types each is, is above and is under.
	 */
	final class Inference {

		final Set<TypeParameter> variables = new LinkedHashSet<>();

		private final Map<TypeParameter, JavaType> exact = new HashMap<>();

		private final Map<TypeParameter, List<JavaType>> lower = new HashMap<>();

		private final Map<TypeParameter, List<JavaType>> upper = new HashMap<>();

		Inference(List<TypeParameter> variables) {
			this.variables.addAll(variables);
		}

		private boolean isVariable(JavaType type) {
			return type instanceof Variable variable && this.variables.contains(variable.parameter());
		}

		private JavaType boxed(JavaType type) {
			return type instanceof Primitive primitive && primitive != Primitive.VOID
					? JavaIntrinsics.boxed(primitive, JavaTypes.this.classes) : type;
		}

		void exactly(TypeParameter parameter, JavaType type) {
			this.exact.putIfAbsent(parameter, type);
		}

		/** A value of a type is given where a formal type is wanted. */
		void lowerBound(JavaType formal, JavaType actual) {
			if (isOpen(actual) && ((Variable) actual).parameter().lower != null) {
				actual = java.util.Objects.requireNonNull(((Variable) actual).parameter().lower);
			}
			if (actual == JavaType.Null.NULL || actual == Primitive.VOID || isOpen(actual)) {
				return;
			}
			actual = boxed(actual);
			if (formal instanceof Variable variable && this.variables.contains(variable.parameter())) {
				this.lower.computeIfAbsent(variable.parameter(), key -> new ArrayList<>()).add(actual);
			}
			else if (formal instanceof ClassType wanted && !wanted.arguments().isEmpty()) {
				JavaType source = actual instanceof ClassType named ? capture(named) : actual;
				ClassType above = asSuper(source, wanted.declaration());
				if (above == null || above.arguments().size() != wanted.arguments().size()) {
					return;
				}
				for (int i = 0; i < wanted.arguments().size(); i++) {
					argument(wanted.arguments().get(i), above.arguments().get(i));
				}
			}
			else if (formal instanceof ArrayType wanted && actual instanceof ArrayType given
					&& given.element().isReference()) {
				lowerBound(wanted.element(), given.element());
			}
		}

		/** A formal type argument takes a given one. */
		private void argument(JavaType formal, JavaType given) {
			if (!(formal instanceof Wildcard wildcard)) {
				same(formal, given);
				return;
			}
			if (wildcard.upper() != null) {
				JavaType inside = given instanceof Wildcard inner ? inner.upper() : given;
				if (inside != null) {
					lowerBound(wildcard.upper(), inside);
				}
			}
			if (wildcard.lower() != null) {
				JavaType inside = given instanceof Wildcard inner ? inner.lower() : given;
				if (inside != null) {
					upperBound(wildcard.lower(), inside);
				}
			}
		}

		/** A formal type and a given one are one type. */
		void same(JavaType formal, JavaType given) {
			if (isOpen(given)) {
				// at least the type it found
				JavaType found = ((Variable) given).parameter().lower;
				if (found != null) {
					lowerBound(formal, found);
				}
				return;
			}
			if (given instanceof Wildcard) {
				return;
			}
			if (formal instanceof Variable variable && this.variables.contains(variable.parameter())) {
				this.exact.putIfAbsent(variable.parameter(), given);
			}
			else if (formal instanceof ClassType wanted && given instanceof ClassType named
					&& wanted.declaration() == named.declaration()
					&& wanted.arguments().size() == named.arguments().size()) {
				for (int i = 0; i < wanted.arguments().size(); i++) {
					JavaType inner = wanted.arguments().get(i);
					JavaType other = named.arguments().get(i);
					if (inner instanceof Wildcard mine && other instanceof Wildcard theirs) {
						if (mine.upper() != null && theirs.upper() != null) {
							same(mine.upper(), theirs.upper());
						}
						if (mine.lower() != null && theirs.lower() != null) {
							same(mine.lower(), theirs.lower());
						}
					}
					else if (!(inner instanceof Wildcard)) {
						same(inner, other);
					}
				}
			}
			else if (formal instanceof ArrayType wanted && given instanceof ArrayType array) {
				same(wanted.element(), array.element());
			}
		}

		/** A value of a formal type is wanted as a type: a call's value as its target. */
		void upperBound(JavaType formal, JavaType target) {
			if (isOpen(target) || target == Primitive.VOID) {
				return;
			}
			if (formal instanceof Variable variable && this.variables.contains(variable.parameter())) {
				this.upper.computeIfAbsent(variable.parameter(), key -> new ArrayList<>()).add(boxed(target));
			}
			else if (formal instanceof ClassType made && !made.arguments().isEmpty()
					&& target instanceof ClassType wanted && !wanted.arguments().isEmpty()) {
				ClassType above = asSuper(made, wanted.declaration());
				if (above == null || above.arguments().size() != wanted.arguments().size()) {
					return;
				}
				for (int i = 0; i < wanted.arguments().size(); i++) {
					JavaType inner = above.arguments().get(i);
					if (wanted.arguments().get(i) instanceof Wildcard wildcard) {
						if (wildcard.upper() != null) {
							upperBound(inner, wildcard.upper());
						}
						if (wildcard.lower() != null) {
							lowerBound(inner, wildcard.lower());
						}
					}
					else {
						same(inner, wanted.arguments().get(i));
					}
				}
			}
			else if (formal instanceof ArrayType made && target instanceof ArrayType wanted) {
				upperBound(made.element(), wanted.element());
			}
		}

		private boolean incorporated;

		/**
		 * What a value given for a parameter says through the bound the parameter is
		 * declared with: a {@code Leaf} given for {@code T extends Comparable<T>}, where
		 * {@code Leaf} is a {@code Comparable<Node>}, makes {@code T} a {@code Node}.
		 */
		private void incorporate() {
			if (this.incorporated) {
				return;
			}
			this.incorporated = true;
			for (TypeParameter parameter : this.variables) {
				List<JavaType> given = this.lower.get(parameter);
				if (given == null) {
					continue;
				}
				for (JavaType bound : parameter.bounds) {
					if (bound instanceof ClassType && mentions(bound, this.variables)) {
						for (JavaType type : List.copyOf(given)) {
							lowerBound(bound, type);
						}
					}
				}
			}
		}

		/** Whether nothing was said of a parameter. */
		boolean isFree(TypeParameter parameter) {
			return resolved(parameter) == null;
		}

		/**
		 * The type a parameter is found to be: the one it has to be, else the least type
		 * above every value given for it, else the type it is wanted under; {@code null}
		 * when nothing was said of it.
		 */
		@Nullable JavaType resolved(TypeParameter parameter) {
			JavaType exact = this.exact.get(parameter);
			if (exact != null) {
				return isVariable(exact) ? null : exact;
			}
			List<JavaType> lower = this.lower.get(parameter);
			if (lower != null) {
				JavaType least = null;
				for (JavaType type : lower) {
					if (!isVariable(type)) {
						least = least == null ? type : lub(least, type);
					}
				}
				if (least != null) {
					return least;
				}
			}
			List<JavaType> upper = this.upper.get(parameter);
			if (upper != null) {
				for (JavaType candidate : upper) {
					if (!isVariable(candidate) && upper.stream().allMatch(other -> isSubtype(candidate, other))) {
						return candidate;
					}
				}
				for (JavaType candidate : upper) {
					if (!isVariable(candidate)) {
						return candidate;
					}
				}
			}
			return null;
		}

		/**
		 * What each parameter is found to be.
		 * @param open whether a parameter nothing was said of is left open; it is left as
		 * itself otherwise, for what is still to come to say
		 */
		Map<TypeParameter, JavaType> solution(boolean open) {
			return solution(open, false);
		}

		/**
		 * What each parameter is found to be.
		 * @param open whether a parameter nothing was said of is left open
		 * @param flexible whether a parameter only the values given for it say something
		 * of is left open above the type they give: the call is an argument of another,
		 * which may need a type above
		 */
		Map<TypeParameter, JavaType> solution(boolean open, boolean flexible) {
			if (open) {
				incorporate();
			}
			Map<TypeParameter, JavaType> found = new LinkedHashMap<>();
			for (TypeParameter parameter : this.variables) {
				JavaType type = resolved(parameter);
				if (type != null) {
					found.put(parameter, type);
				}
			}
			// what one parameter is found to be may name another
			for (int round = 0; round < 3; round++) {
				for (Map.Entry<TypeParameter, JavaType> entry : found.entrySet()) {
					entry.setValue(substitute(entry.getValue(), found));
				}
			}
			if (open) {
				for (TypeParameter parameter : this.variables) {
					JavaType type = found.get(parameter);
					if (type == null) {
						found.put(parameter, open(parameter, found, null));
					}
					else if (flexible && !this.exact.containsKey(parameter) && this.lower.containsKey(parameter)
							&& !isOpen(type)) {
						found.put(parameter, open(parameter, found, type));
					}
				}
			}
			return found;
		}

		/**
		 * The parameter whose bounds no one type satisfies, or {@code null}: a value
		 * given for it is not the type it has to be, or the type found for it is not
		 * under a type it is wanted under.
		 */
		@Nullable TypeParameter conflict(Map<TypeParameter, JavaType> found) {
			for (TypeParameter parameter : this.variables) {
				JavaType type = found.get(parameter);
				if (type == null || isOpen(type)) {
					continue;
				}
				JavaType exact = this.exact.get(parameter);
				for (JavaType given : this.lower.getOrDefault(parameter, List.of())) {
					if (exact != null && !mentions(exact, this.variables) && !mentions(given, this.variables)
							&& !isSubtype(given, exact)) {
						return parameter;
					}
				}
				for (JavaType wanted : this.upper.getOrDefault(parameter, List.of())) {
					if (!mentions(wanted, this.variables) && !isSubtype(type, wanted)) {
						return parameter;
					}
				}
			}
			return null;
		}

		/** How many types the values given for a parameter are of. */
		int given(TypeParameter parameter) {
			List<JavaType> distinct = new ArrayList<>();
			for (JavaType type : this.lower.getOrDefault(parameter, List.of())) {
				if (distinct.stream().noneMatch(known -> known.equals(type))) {
					distinct.add(type);
				}
			}
			return distinct.size();
		}

		/**
		 * Whether each parameter found is within the bounds it is declared with.
		 */
		boolean withinBounds(Map<TypeParameter, JavaType> found) {
			for (TypeParameter parameter : this.variables) {
				JavaType type = found.get(parameter);
				if (type == null || isOpen(type)) {
					continue;
				}
				for (JavaType bound : parameter.bounds) {
					JavaType wanted = substitute(bound, found);
					if (!mentions(wanted, this.variables) && !isSubtype(type, wanted)) {
						return false;
					}
				}
			}
			return true;
		}

	}

}
