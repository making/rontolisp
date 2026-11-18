package am.ik.rontolisp.java;

import am.ik.rontolisp.LispSymbol;

/**
 * How the names of a Java program are spelled as Common Lisp symbol names.
 *
 * <p>
 * A name is kept VERBATIM whenever it contains an ASCII lowercase letter: every canonical
 * name the pipeline dispatches on is upcased ({@code .kb/reader-case-upcase.md}), so such
 * a name can never reach a built-in, a lambda-list keyword or {@code T}/{@code NIL}. Any
 * other name ({@code X}, {@code T}, {@code IO}, {@code A.MAX}) is escaped behind
 * {@link #PREFIX}. An identifier holds no {@code %}, so the prefix is no identifier's
 * start and the mapping is injective.
 *
 * <p>
 * A class is named by {@link JavaClass#lispName}; a method's function carries its
 * signature, {@code Shapes.apply(Scorer,String)}, so overloads never meet; what the
 * lowering generates for itself starts with a {@code %} and holds a lowercase letter
 * ({@code %t3}, {@code %fn}); the run-time helpers are {@code rontolisp::%java-} names.
 */
final class JavaNames {

	/** What an escaped name starts with. Contains a lowercase letter on purpose. */
	static final String PREFIX = "j%";

	private JavaNames() {
	}

	static String mangle(String name) {
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c >= 'a' && c <= 'z') {
				return name;
			}
		}
		return PREFIX + name;
	}

	static LispSymbol symbol(String name) {
		return new LispSymbol(mangle(name));
	}

	/**
	 * The structure a class is, named as the class is at run time ({@code Outer$Inner},
	 * {@code java.lang.ThreadLocal}): the name is what {@code Object.toString()} reads.
	 */
	static LispSymbol structure(JavaClass declaration) {
		return symbol(declaration.binaryName);
	}

	/** The structure's predicate: {@code instanceof} of a class. */
	static LispSymbol predicate(JavaClass declaration) {
		return symbol(declaration.lispName() + "?");
	}

	/** The structure's constructor, which runs no Java constructor. */
	static LispSymbol allocator(JavaClass declaration) {
		return symbol("%make-" + declaration.lispName());
	}

	/** What the accessors of a class's own fields start with. */
	static LispSymbol accessorPrefix(JavaClass declaration) {
		return new LispSymbol(declaration.lispName() + ".");
	}

	/** An instance field's slot: the accessor is the class's prefix and this. */
	static LispSymbol slot(String field) {
		return symbol(field);
	}

	static LispSymbol accessor(JavaClass.Field field) {
		return new LispSymbol(field.owner.lispName() + "." + mangle(field.slot()));
	}

	/** A static field's global variable. */
	static LispSymbol global(JavaClass.Field field) {
		return symbol(field.owner.lispName() + "." + field.name);
	}

	/**
	 * A method's function; a constructor's is the one run on an instance made already.
	 */
	static LispSymbol function(JavaClass.Method method) {
		return symbol(method.owner.lispName() + "." + method.signature());
	}

	/** The function running a class's static initialization at its first use. */
	static LispSymbol initializer(JavaClass declaration) {
		return symbol(declaration.lispName() + ".<clinit>");
	}

	/** The variable telling that a class's static initialization has started. */
	static LispSymbol initialized(JavaClass declaration) {
		return symbol(declaration.lispName() + ".<clinit>%done");
	}

	/** The function making an instance and running a constructor on it. */
	static LispSymbol creator(JavaClass.Method constructor) {
		return symbol(constructor.owner.lispName() + ".new" + constructor.parameterList());
	}

	/** The function choosing a method's implementation by the receiver's class. */
	static LispSymbol dispatcher(JavaClass receiver, JavaClass.Method method) {
		return symbol(receiver.lispName() + "." + method.signature() + "%v");
	}

	/** The function answering {@code instanceof} of an interface. */
	static LispSymbol interfaceTest(JavaClass declaration) {
		return symbol(declaration.lispName() + "%is");
	}

}
