package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import am.ik.rontolisp.LispString;
import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaBoundary.Kind;
import am.ik.rontolisp.java.JavaBoundary.Rep;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaClasses.Unit;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The program's {@code @WitImport} interfaces: a Java interface standing for a WIT
 * interface, its abstract methods the WIT functions the program calls
 * ({@code .kb/java-frontend.md}, "WIT imports"). Each is checked against the WIT on every
 * target, member by member and type by type, against the one mapping of a WIT type onto a
 * Java type; the WIT is read through the host boundary ({@link JavaBoundary}), so a
 * member is the one the {@code rontolisp:wit-import} binding it names.
 *
 * <p>
 * What implements the interface is found with {@code ServiceLoader}: the program's own
 * provider on the interpreter, the JVM and a JDK, the host on a WASM target
 * ({@link JavaBoundary#hostImports}). There the provider is a class of generated Java
 * source, the BINDING, lowered as far as the program reaches it like an annotation's
 * class: each method converts its arguments to the boundary's values (the settled Common
 * Lisp tier, {@code rontolisp.internal.Wit}), calls the member the directive binds
 * through a native of its own, and converts the answer back -- a {@code result}'s error
 * arm a {@code WitException}. The directive names the members the program's last pass
 * reached, so a component imports what the program calls.
 *
 * <p>
 * An {@code async func} is a method that waits for the host's answer, as a call does: the
 * directive blocks on it ({@code :blocking t}). A {@code stream<T>} of values the host
 * answers is a {@code WitStream<T>}, a {@code future<T>} a {@code WitFuture<T>}
 * ({@code rontolisp-java-api}), each a class of the binding reading through the built-ins
 * of an alias of its type the directive adds ({@code :aliases}).
 */
final class JavaWitImports {

	/** The annotation, in the library a program names it from. */
	static final String WIT_IMPORT = "am.ik.rontolisp.wit.WitImport";

	/** The exception a {@code result}'s error arm is thrown as. */
	static final String WIT_EXCEPTION = "am.ik.rontolisp.wit.WitException";

	/** What a WIT {@code stream<T>} the host answers is. */
	static final String WIT_STREAM = "am.ik.rontolisp.wit.WitStream";

	/** What a WIT {@code future<T>} the host answers is. */
	static final String WIT_FUTURE = "am.ik.rontolisp.wit.WitFuture";

	/** The library class of the boundary's values. */
	private static final String VALUES = "rontolisp.internal.Wit";

	/** The keywords and literals no Java name can be. */
	private static final Set<String> RESERVED = Set.of("abstract", "assert", "boolean", "break", "byte", "case",
			"catch", "char", "class", "const", "continue", "default", "do", "double", "else", "enum", "extends",
			"final", "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface",
			"long", "native", "new", "package", "private", "protected", "public", "return", "short", "static",
			"strictfp", "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
			"volatile", "while", "true", "false", "null", "_");

	private final JavaClasses classes;

	/** The boundary the program is lowered against. */
	JavaBoundary boundary = JavaBoundary.NONE;

	/**
	 * Each interface asked about: its import, or none for one no {@code @WitImport}
	 * names.
	 */
	private final Map<JavaClass, Optional<Import>> imports = new IdentityHashMap<>();

	/** The binding of each interface, made on a WASM target. */
	private final Map<JavaClass, JavaClass> bindings = new IdentityHashMap<>();

	/** The natives of the bindings, by member ({@code p.Store$Wit.m0(Object)}). */
	final Map<String, JavaEmitter> natives = new HashMap<>();

	/**
	 * The type aliases each binding reads a stream or a future through, by interface:
	 * each alias's name and the type it names, which the directive adds
	 * ({@code :aliases}).
	 */
	private final Map<JavaClass, Map<String, String>> aliases = new IdentityHashMap<>();

	/**
	 * The members the pass being lowered reached: by interface, each member's bound name.
	 */
	private final Map<JavaClass, Map<String, String>> reached = new LinkedHashMap<>();

	JavaWitImports(JavaClasses classes) {
		this.classes = classes;
	}

	/**
	 * A Java interface standing for a WIT interface, checked.
	 *
	 * @param declaration the interface
	 * @param id the WIT interface's canonical id
	 * @param path the WIT as the directive names it
	 * @param functions its functions, constructors and statics, each by the Java method
	 * that is it
	 * @param resources the resources the methods reach, by WIT name, in the order met
	 * @param members the WIT interface's member names, in its order: the directive's
	 */
	record Import(JavaClass declaration, String id, String path, List<Bound> functions, Map<String, Resource> resources,
			List<String> members) {
	}

	/**
	 * A Java method and the WIT member it is.
	 *
	 * @param method the method
	 * @param function the member
	 */
	record Bound(JavaClass.Method method, JavaBoundary.Function function) {
	}

	/**
	 * A WIT resource and the Java interface standing for it.
	 *
	 * @param name its WIT name
	 * @param declaration the interface
	 * @param methods its methods, each by the Java method that is it
	 * @param drop the member releasing it, bound to {@code close()}, or {@code null}
	 */
	record Resource(String name, JavaClass declaration, List<Bound> methods, JavaBoundary.@Nullable Function drop) {
	}

	// ---- the annotation

	/**
	 * The import a class stands for, checked, or {@code null} when no {@code @WitImport}
	 * annotates it.
	 * @throws LispReadException where the annotation, the WIT or a declaration is wrong,
	 * at the declaration
	 */
	@Nullable Import of(JavaClass declaration) {
		Optional<Import> known = this.imports.get(declaration);
		if (known != null) {
			return known.orElse(null);
		}
		JavaSyntax.Annotation written = annotation(declaration);
		if (written == null) {
			this.imports.put(declaration, Optional.empty());
			return null;
		}
		Unit unit = this.classes.unit(declaration);
		Source source = unit.source();
		if (!declaration.isInterface || declaration.isAnnotation) {
			throw JavaClasses.error(source, written.pos(), "@WitImport annotates an interface, whose abstract methods "
					+ "are the functions of the WIT interface; " + declaration.simpleName() + " is no interface");
		}
		if (declaration.isLocal || !declaration.typeParameters.isEmpty()) {
			throw JavaClasses.error(source, written.pos(),
					"@WitImport annotates an interface of no type parameter that is no local one; "
							+ declaration.simpleName() + " is not");
		}
		String reference = literal(written, "WitImport", "value", unit);
		String wit = literal(written, "WitImport", "wit", unit);
		// found as Class.getResource finds a name: below the package of the class, or
		// from the top of a root after a '/', by the loader of the class
		String packageName = this.classes.packageOf(declaration);
		String name = JavaFiles.below(wit.startsWith("/") ? wit.substring(1)
				: packageName == null || packageName.isEmpty() ? wit : packageName.replace('.', '/') + "/" + wit);
		if (name == null) {
			throw JavaClasses.error(source, written.pos(),
					"@WitImport: cannot read WIT " + wit + ": it names a resource above the top of a root");
		}
		boolean tree = !source.dependency();
		JavaBoundary.WitInterface described;
		try {
			described = this.boundary.importInterface(wit, this.classes.resourcePaths(name, tree), reference);
		}
		catch (LispReadException ex) {
			throw JavaClasses.error(source, written.pos(), "@WitImport: " + ex.getMessage());
		}
		if (described == null) {
			throw JavaClasses.error(source, written.pos(), "@WitImport: cannot read WIT " + wit + ": no "
					+ (tree ? "file of the source tree and no " : "") + "root of the source path holds " + name);
		}
		Import made = new Checker(declaration, described, source).check();
		this.imports.put(declaration, Optional.of(made));
		return made;
	}

	/**
	 * Checks every {@code @WitImport} interface the program has, and refuses the
	 * annotation on a class that is none.
	 */
	void checkAll() {
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			if (declaration.declaration != null && declaration.source != null && !declaration.source.library()) {
				of(declaration);
			}
		}
	}

	/** The {@code @WitImport} written on a class, or {@code null}. */
	private JavaSyntax.@Nullable Annotation annotation(JavaClass declaration) {
		JavaSyntax.ClassDeclaration written = declaration.declaration;
		if (written == null || declaration.intrinsic || declaration.source == null || declaration.source.library()) {
			return null;
		}
		Unit unit = null;
		for (JavaSyntax.Annotation annotation : written.annotations()) {
			String name = annotation.name();
			if (!name.equals("WitImport") && !name.equals(WIT_IMPORT)) {
				continue;
			}
			unit = unit == null ? this.classes.unit(declaration) : unit;
			JavaClass type = this.classes.annotationType(annotation, declaration, unit);
			if (type != null && type.name.equals(WIT_IMPORT)) {
				return annotation;
			}
		}
		return null;
	}

	/**
	 * The string literal written for an element of an annotation the compiler reads.
	 * @param annotation the annotation's simple name, for a message
	 */
	static String literal(JavaSyntax.Annotation written, String annotation, String element, Unit unit) {
		for (JavaSyntax.ElementPair pair : written.pairs()) {
			if (!pair.name().equals(element)) {
				continue;
			}
			if (pair.value() instanceof JavaSyntax.ElementText text) {
				List<JavaToken> tokens = JavaLexer.lex(unit.source().text().substring(text.from(), text.to()), null);
				if (tokens.size() == 2 && tokens.get(0).kind() == JavaToken.Kind.STRING) {
					return tokens.get(0).text();
				}
			}
			throw JavaClasses.error(unit.source(), written.pos(), "@" + annotation + "'s " + element
					+ " is a string literal, which the compiler reads: the WIT names nothing it computes");
		}
		throw JavaClasses.error(unit.source(), written.pos(),
				"annotation @" + annotation + " is missing a default value for the element '" + element + "'");
	}

	// ---- the names

	/**
	 * A WIT name as a Java method's or a component's: {@code list-keys} ->
	 * {@code listKeys}.
	 */
	static String lowerCamel(String label) {
		String[] words = label.split("-");
		StringBuilder name = new StringBuilder(words[0].toLowerCase(Locale.ROOT));
		for (int i = 1; i < words.length; i++) {
			name.append(capitalized(words[i]));
		}
		String made = name.toString();
		return RESERVED.contains(made) ? made + "_" : made;
	}

	/** A WIT name as a Java type's: {@code key-response} -> {@code KeyResponse}. */
	static String upperCamel(String label) {
		StringBuilder name = new StringBuilder();
		for (String word : label.split("-")) {
			name.append(capitalized(word));
		}
		return name.toString();
	}

	/**
	 * A WIT label as an enum constant's: {@code no-such-store} -> {@code NO_SUCH_STORE}.
	 */
	static String constant(String label) {
		return label.replace('-', '_').toUpperCase(Locale.ROOT);
	}

	// a word of lower-case letters capitalized; an acronym, a word of capitals, as it is
	private static String capitalized(String word) {
		return word.isEmpty() ? word : Character.toUpperCase(word.charAt(0)) + word.substring(1);
	}

	// ---- the check

	/** What one {@code @WitImport} interface is checked by. */
	private final class Checker {

		private final JavaClass declaration;

		private final JavaBoundary.WitInterface wit;

		private final Source source;

		/** The resources the interface defines, which a handle may be of. */
		private final Map<String, JavaBoundary.Function> drops = new LinkedHashMap<>();

		/** The resources met, in order. */
		private final Map<String, Resource> resources = new LinkedHashMap<>();

		/** The named types checked, by the Java class standing for each. */
		private final Set<JavaClass> checked = new java.util.HashSet<>();

		Checker(JavaClass declaration, JavaBoundary.WitInterface wit, Source source) {
			this.declaration = declaration;
			this.wit = wit;
			this.source = source;
			for (JavaBoundary.Function member : wit.members()) {
				if (member.kind() == Kind.DROP) {
					this.drops.put(Objects.requireNonNull(member.resource()), member);
				}
			}
		}

		Import check() {
			Map<String, JavaBoundary.Function> functions = new LinkedHashMap<>();
			for (JavaBoundary.Function member : this.wit.members()) {
				switch (member.kind()) {
					case FUNCTION -> named(functions, lowerCamel(member.function()), member);
					case CONSTRUCTOR, STATIC -> named(functions, lowerCamel(member.name()), member);
					default -> {
					}
				}
			}
			List<Bound> bound = new ArrayList<>();
			for (JavaClass.Method method : abstractMethods(this.declaration)) {
				JavaBoundary.Function member = functions.get(method.name);
				if (member == null) {
					throw at(method,
							method.name + " names no function of " + this.wit.id() + ", whose functions are "
									+ (functions.isEmpty() ? "none" : String.join(", ", functions.keySet()))
									+ " (a resource's methods are its interface's)");
				}
				bound.add(bind(method, member));
			}
			return new Import(this.declaration, this.wit.id(), this.wit.path(), List.copyOf(bound),
					java.util.Collections.unmodifiableMap(this.resources),
					this.wit.members().stream().map(JavaBoundary.Function::name).toList());
		}

		private void named(Map<String, JavaBoundary.Function> names, String name, JavaBoundary.Function member) {
			JavaBoundary.Function other = names.put(name, member);
			if (other != null) {
				throw JavaClasses.error(this.source, Objects.requireNonNull(this.declaration.declaration).pos(),
						"@WitImport: " + other.name() + " and " + member.name() + " of " + this.wit.id() + " are both "
								+ name + " in Java (" + member.at() + ")");
			}
		}

		/** A method checked against the member it is. */
		private Bound bind(JavaClass.Method method, JavaBoundary.Function member) {
			String what = method.name + " (" + member.name() + " of " + this.wit.id() + ", " + member.at() + ")";
			// an async func is a call that waits for the host's answer, as any call does
			// (the directive blocks on it)
			List<JavaBoundary.Param> params = member.kind() == Kind.METHOD
					? member.params().subList(1, member.params().size()) : member.params();
			if (method.parameters.size() != params.size()) {
				throw at(method, what + " takes " + params.size() + " argument" + (params.size() == 1 ? "" : "s")
						+ ", not " + method.parameters.size());
			}
			for (int i = 0; i < params.size(); i++) {
				JavaBoundary.Param param = params.get(i);
				if (param.type().rep() == Rep.RESULT) {
					throw at(method, what + ": its parameter " + param.name() + " is a result, which the Java subset "
							+ "carries only as a function's answer");
				}
				String async = asyncIn(param.type());
				if (async != null) {
					throw at(method, what + ": its parameter " + param.name() + " carries " + async
							+ ", which the Java subset carries only where the host answers one");
				}
				expect(param.type(), method.parameters.get(i), false, method, what + ": its parameter " + param.name());
			}
			JavaBoundary.Type result = member.result();
			if (result == null) {
				expectVoid(method, what);
			}
			else if (result.rep() == Rep.RESULT) {
				JavaBoundary.Type ok = result.element();
				if (ok == null) {
					expectVoid(method, what);
				}
				else {
					expect(ok, method.returnType, false, method, what + ": its result's ok arm");
				}
				JavaBoundary.Type error = result.error();
				if (error != null) {
					// thrown as a WitException's value: the Java type is the mapping's
					// own
					expect(error, null, true, method, what + ": its result's error arm");
				}
			}
			else {
				expect(result, method.returnType, false, method, what + ": its result");
			}
			return new Bound(method, member);
		}

		private void expectVoid(JavaClass.Method method, String what) {
			if (method.returnType != Primitive.VOID) {
				throw at(method, what + " answers nothing, so it is void, not " + method.returnType);
			}
		}

		/**
		 * Checks a declared type against the WIT type's mapping.
		 * @param declared the type, or {@code null} where none is written (an error arm):
		 * then what the mapping names is checked to exist
		 * @param boxed whether the place takes a reference type (a type argument)
		 */
		private void expect(JavaBoundary.Type wit, @Nullable JavaType declared, boolean boxed, JavaClass.Method method,
				String what) {
			String refusal = refusal(wit, declared == null);
			if (refusal != null) {
				throw at(method, what + " is " + wit.wit() + ", " + refusal);
			}
			if (declared != null && !matches(wit, declared, boxed, method, what)) {
				throw at(method, what + " is " + wit.wit() + ", which is "
						+ javaText(this.declaration, wit, boxed, declared, true) + " in Java, not " + declared);
			}
			definitions(wit, declared, method, what);
		}

		/** Whether a declared type is the one the WIT type maps onto. */
		private boolean matches(JavaBoundary.Type wit, JavaType declared, boolean boxed, JavaClass.Method method,
				String what) {
			return switch (wit.rep()) {
				case INT, BIGNUM_INT, FLOAT, BOOLEAN, CHARACTER ->
					boxed ? isClass(declared, box(scalar(wit))) : declared == scalar(wit);
				case STRING -> isClass(declared, "java.lang.String");
				case BYTE_STRING -> declared.equals(new ArrayType(Primitive.BYTE));
				case LIST, NIL_OR_VALUE -> declared instanceof ClassType type
						&& type.declaration().name
							.equals(wit.rep() == Rep.LIST ? "java.util.List" : "java.util.Optional")
						&& type.arguments().size() == 1
						&& matches(Objects.requireNonNull(wit.element()), type.arguments().get(0), true, method, what);
				case TUPLE_LIST -> {
					JavaClass record = declared instanceof ClassType type && type.arguments().isEmpty()
							? type.declaration() : null;
					List<JavaClass.Field> components = record != null && record.isRecord ? components(record)
							: List.of();
					boolean all = record != null && record.isRecord && components.size() == wit.parts().size();
					for (int i = 0; all && i < components.size(); i++) {
						all = matches(Objects.requireNonNull(wit.parts().get(i).type()), components.get(i).type, false,
								method, what);
					}
					yield all;
				}
				case KEYWORD_LIST -> declared instanceof ClassType type
						&& type.declaration().name.equals("java.util.Set") && type.arguments().size() == 1
						&& type.arguments().get(0).equals(new ClassType(member(wit, method, what), List.of()));
				case STREAM_HANDLE -> declared instanceof ClassType type && type.declaration().name.equals(WIT_STREAM)
						&& type.arguments().size() == 1
						&& matches(Objects.requireNonNull(wit.element()), type.arguments().get(0), true, method, what);
				case FUTURE_HANDLE -> declared instanceof ClassType type && type.declaration().name.equals(WIT_FUTURE)
						&& type.arguments().size() == 1
						&& futureMatches(Objects.requireNonNull(wit.element()), type.arguments().get(0), method, what);
				case PLIST, TAGGED_LIST, KEYWORD, HANDLE ->
					declared.equals(new ClassType(member(wit, method, what), List.of()));
				default -> false;
			};
		}

		/**
		 * Whether a future's value type is what its payload maps onto: a result's ok arm
		 * ({@code Void} for none), or the payload.
		 */
		private boolean futureMatches(JavaBoundary.Type payload, JavaType declared, JavaClass.Method method,
				String what) {
			if (payload.rep() != Rep.RESULT) {
				return matches(payload, declared, true, method, what);
			}
			JavaBoundary.Type ok = payload.element();
			return ok == null ? isClass(declared, "java.lang.Void") : matches(ok, declared, true, method, what);
		}

		/**
		 * Checks each named type a type holds against its definition, once: the member
		 * class of its name, a record's components, a variant's cases, an enum's
		 * constants, a resource's methods.
		 */
		private void definitions(JavaBoundary.Type wit, @Nullable JavaType declared, JavaClass.Method method,
				String what) {
			switch (wit.rep()) {
				case LIST, NIL_OR_VALUE, STREAM_HANDLE ->
					definitions(Objects.requireNonNull(wit.element()), argument(declared), method, what);
				case FUTURE_HANDLE -> {
					JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
					if (payload.rep() != Rep.RESULT) {
						definitions(payload, argument(declared), method, what);
						break;
					}
					if (payload.element() != null) {
						definitions(payload.element(), argument(declared), method, what);
					}
					if (payload.error() != null) {
						// thrown by get() as a WitException's value, as a result's error
						// arm is
						expect(payload.error(), null, true, method, what + ": its future's error arm");
					}
				}
				case TUPLE_LIST -> {
					if (declared == null) {
						throw at(method, what + " is " + wit.wit() + ", a tuple, which is a record of the program's "
								+ "where a type is written for it, and none is written for a result's error arm");
					}
					List<JavaClass.Field> components = components(((ClassType) declared).declaration());
					for (int i = 0; i < components.size(); i++) {
						definitions(Objects.requireNonNull(wit.parts().get(i).type()), components.get(i).type, method,
								what);
					}
				}
				case PLIST, TAGGED_LIST, KEYWORD, KEYWORD_LIST, HANDLE ->
					definition(wit, member(wit, method, what), method);
				default -> {
				}
			}
		}

		/**
		 * Why a type has no Java value, itself or a structural type it holds (a named
		 * type's own are its definition's to tell), or {@code null}.
		 * @param arm whether it is a result's error arm, which no type is written for
		 */
		private @Nullable String refusal(JavaBoundary.Type wit, boolean arm) {
			String own = switch (wit.rep()) {
				case STREAM_HANDLE -> {
					JavaBoundary.Type element = wit.element();
					yield element == null ? "a bare stream, which carries no value"
							: element.rep() == Rep.INT && "u8".equals(element.definition())
									? "a stream<u8>, which the Java subset does not carry yet"
									: element.rep() == Rep.HANDLE
											? "a stream of resource handles, which the Java subset does not carry yet"
											: null;
				}
				case FUTURE_HANDLE -> {
					JavaBoundary.Type payload = wit.element();
					yield payload == null ? "a bare future, which carries no value" : switch (payload.rep()) {
						// the read of a future its writer dropped answers nil, so a
						// value the boundary spells nil cannot be told from it
						case BOOLEAN, LIST, NIL_OR_VALUE, KEYWORD_LIST ->
							"a future of a type one of whose values the boundary cannot tell from a future its "
									+ "writer dropped";
						default -> null;
					};
				}
				case UNSUPPORTED -> "a type with no rontolisp value";
				case RESULT -> "a result, which the Java subset carries only as a function's answer";
				case TUPLE_LIST -> arm ? "a tuple, which is a record of the program's where a type is written for it,"
						+ " and none is written for a result's error arm" : null;
				// the boundary spells none as nil, so an element whose value can be nil
				// cannot be told from it
				case NIL_OR_VALUE -> switch (Objects.requireNonNull(wit.element()).rep()) {
					case BOOLEAN, LIST, KEYWORD_LIST, NIL_OR_VALUE ->
						"an option of a type one of whose values the boundary cannot tell from none";
					default -> null;
				};
				default -> null;
			};
			if (own != null) {
				return own;
			}
			List<JavaBoundary.Type> held = new ArrayList<>();
			if (wit.rep() == Rep.LIST || wit.rep() == Rep.NIL_OR_VALUE || wit.rep() == Rep.STREAM_HANDLE) {
				held.add(Objects.requireNonNull(wit.element()));
			}
			if (wit.rep() == Rep.FUTURE_HANDLE) {
				JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
				if (payload.rep() != Rep.RESULT) {
					held.add(payload);
				}
				else if (payload.element() != null) {
					// the error arm is checked as a result's is (definitions)
					held.add(payload.element());
				}
			}
			if (wit.rep() == Rep.TUPLE_LIST) {
				wit.parts().forEach(part -> held.add(Objects.requireNonNull(part.type())));
			}
			for (JavaBoundary.Type inner : held) {
				String refused = refusal(inner, arm);
				if (refused != null) {
					return "holding " + inner.wit() + ", " + refused;
				}
			}
			return null;
		}

		/** The class a named type is: the interface's member type of its name. */
		private JavaClass member(JavaBoundary.Type wit, JavaClass.Method method, String what) {
			String definition = Objects.requireNonNull(wit.definition());
			String name = upperCamel(definition);
			JavaClass named = this.declaration.members.get(name);
			String kind = switch (wit.rep()) {
				case PLIST -> "a record";
				case TAGGED_LIST -> "a sealed interface";
				case KEYWORD, KEYWORD_LIST -> "an enum";
				default -> "an interface";
			};
			if (named == null) {
				throw at(method, what + " is " + wit.wit() + ", which is " + this.declaration.simpleName() + "." + name
						+ " in Java, " + kind + " the @WitImport interface declares; it declares none of that name");
			}
			return named;
		}

		/** Checks the class a named type is against its definition, once. */
		private void definition(JavaBoundary.Type wit, JavaClass named, JavaClass.Method method) {
			if (!this.checked.add(named)) {
				return;
			}
			String definition = Objects.requireNonNull(wit.definition());
			String what = this.declaration.simpleName() + "." + named.simpleName() + " (" + definition + " of "
					+ this.wit.id() + ")";
			switch (wit.rep()) {
				case PLIST -> {
					if (!named.isRecord) {
						throw at(named, what + " is a record in Java");
					}
					Map<String, JavaBoundary.Part> fields = new LinkedHashMap<>();
					for (JavaBoundary.Part part : wit.parts()) {
						fields.put(lowerCamel(part.label()), part);
					}
					List<JavaClass.Field> components = components(named);
					if (!fields.keySet().equals(names(components))) {
						throw at(named, what + " has the components " + String.join(", ", fields.keySet()) + ", not "
								+ String.join(", ", names(components)));
					}
					for (JavaClass.Field component : components) {
						JavaBoundary.Part field = Objects.requireNonNull(fields.get(component.name));
						expect(Objects.requireNonNull(field.type()), component.type, false, method,
								what + ": its field " + field.label());
					}
				}
				case TAGGED_LIST -> {
					if (!named.isInterface || !named.isSealed) {
						throw at(named, what + " is a variant: a sealed interface in Java, a record each case");
					}
					Map<String, JavaBoundary.Part> cases = new LinkedHashMap<>();
					for (JavaBoundary.Part part : wit.parts()) {
						cases.put(upperCamel(part.label()), part);
					}
					Set<String> permitted = new LinkedHashSet<>();
					for (JavaClass subclass : named.permits) {
						permitted.add(subclass.simpleName());
					}
					if (!cases.keySet().equals(permitted)) {
						throw at(named, what + " permits the records " + String.join(", ", cases.keySet())
								+ ", one a case, not " + String.join(", ", permitted));
					}
					for (JavaClass subclass : named.permits) {
						JavaBoundary.Part found = Objects.requireNonNull(cases.get(subclass.simpleName()));
						List<JavaClass.Field> components = subclass.isRecord ? components(subclass) : List.of();
						int wanted = found.type() == null ? 0 : 1;
						if (!subclass.isRecord || components.size() != wanted) {
							throw at(subclass, what + ": its case " + found.label() + " is a record of "
									+ (wanted == 0 ? "no component" : "one component, its payload"));
						}
						if (found.type() != null) {
							expect(found.type(), components.get(0).type, false, method,
									what + ": the payload of its case " + found.label());
						}
					}
				}
				case KEYWORD, KEYWORD_LIST -> {
					if (!named.isEnum) {
						throw at(named, what + " is an enum in Java");
					}
					Set<String> labels = new LinkedHashSet<>();
					for (JavaBoundary.Part part : wit.parts()) {
						labels.add(constant(part.label()));
					}
					Set<String> constants = new LinkedHashSet<>();
					for (JavaClass.Field field : named.fields) {
						if (field.isConstant) {
							constants.add(field.name);
						}
					}
					if (!labels.equals(constants)) {
						throw at(named, what + " has the constants " + String.join(", ", labels) + ", not "
								+ String.join(", ", constants));
					}
				}
				case HANDLE -> resource(definition, named);
				default -> throw new IllegalStateException(wit.rep() + " is no named type");
			}
		}

		/** A resource's interface checked: each abstract method a method of it. */
		private void resource(String name, JavaClass named) {
			JavaBoundary.Function drop = this.drops.get(name);
			if (drop == null) {
				throw at(named,
						this.declaration.simpleName() + "." + named.simpleName() + " is " + name
								+ ", a resource of another interface than " + this.wit.id()
								+ ", whose methods the Java subset does not bind yet");
			}
			if (!named.isInterface || named.isAnnotation) {
				throw at(named, this.declaration.simpleName() + "." + named.simpleName() + " is the resource " + name
						+ " of " + this.wit.id() + ": an interface in Java");
			}
			Map<String, JavaBoundary.Function> methods = new LinkedHashMap<>();
			for (JavaBoundary.Function member : this.wit.members()) {
				if (member.kind() == Kind.METHOD && name.equals(member.resource())) {
					named(methods, lowerCamel(member.function()), member);
				}
			}
			// put before the methods are checked: one may take or answer its own resource
			List<Bound> bound = new ArrayList<>();
			JavaBoundary.Function released = null;
			Resource made = new Resource(name, named, bound, null);
			this.resources.put(name, made);
			for (JavaClass.Method method : abstractMethods(named)) {
				if (method.name.equals("close") && method.parameters.isEmpty()) {
					expectVoid(method, "close (" + drop.name() + " of " + this.wit.id() + ", " + drop.at() + ")");
					released = drop;
					continue;
				}
				JavaBoundary.Function member = methods.get(method.name);
				if (member == null) {
					throw at(method,
							method.name + " names no method of the resource " + name + " of " + this.wit.id()
									+ ", whose methods are "
									+ (methods.isEmpty() ? "none" : String.join(", ", methods.keySet()))
									+ ", and close() its release");
				}
				bound.add(bind(method, member));
			}
			this.resources.put(name, new Resource(name, named, List.copyOf(bound), released));
		}

		private LispReadException at(JavaClass.Method method, String message) {
			JavaSyntax.Method written = method.declaration;
			if (written != null && method.owner.source != null) {
				return JavaClasses.error(method.owner.source, written.pos(), message);
			}
			return at(this.declaration, message);
		}

		private LispReadException at(JavaClass named, String message) {
			JavaSyntax.ClassDeclaration written = named.declaration;
			return JavaClasses.error(named.source == null ? this.source : named.source,
					written == null ? 0 : written.pos(), message);
		}

	}

	/** The abstract methods of an interface, its own and inherited, but Object's. */
	static List<JavaClass.Method> abstractMethods(JavaClass declaration) {
		Map<String, JavaClass.Method> methods = new LinkedHashMap<>();
		List<JavaClass> pending = new ArrayList<>(List.of(declaration));
		Set<JavaClass> seen = new java.util.HashSet<>();
		while (!pending.isEmpty()) {
			JavaClass type = pending.remove(0);
			if (!seen.add(type)) {
				continue;
			}
			for (JavaClass.Method method : type.methods) {
				if (!method.isStatic && !method.isConstructor() && !method.isPrivate && !method.isObjectMethod()) {
					methods.putIfAbsent(method.signature(), method);
				}
			}
			pending.addAll(type.interfaces);
		}
		return methods.values().stream().filter(method -> method.isAbstract).toList();
	}

	/** A record's components, in order. */
	private static List<JavaClass.Field> components(JavaClass record) {
		List<JavaClass.Field> components = new ArrayList<>();
		JavaSyntax.ClassDeclaration written = Objects.requireNonNull(record.declaration);
		for (JavaSyntax.Parameter component : written.components()) {
			for (JavaClass.Field field : record.fields) {
				if (!field.isStatic && field.name.equals(component.name())) {
					components.add(field);
				}
			}
		}
		return components;
	}

	/**
	 * The first stream or future a type carries, as WIT spells it, or {@code null}: what
	 * a program cannot hand the host, which writes the values of what it hands over.
	 */
	private static @Nullable String asyncIn(JavaBoundary.Type wit) {
		if (wit.rep() == Rep.STREAM_HANDLE || wit.rep() == Rep.FUTURE_HANDLE) {
			return wit.wit();
		}
		List<JavaBoundary.Type> held = new ArrayList<>();
		if (wit.element() != null) {
			held.add(wit.element());
		}
		if (wit.error() != null) {
			held.add(wit.error());
		}
		for (JavaBoundary.Part part : wit.parts()) {
			if (part.type() != null) {
				held.add(part.type());
			}
		}
		for (JavaBoundary.Type inner : held) {
			String found = asyncIn(inner);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	private static Set<String> names(List<JavaClass.Field> fields) {
		Set<String> names = new LinkedHashSet<>();
		fields.forEach(field -> names.add(field.name));
		return names;
	}

	private static boolean isClass(JavaType type, String name) {
		return type instanceof ClassType named && named.declaration().name.equals(name) && named.arguments().isEmpty();
	}

	private static Primitive scalar(JavaBoundary.Type wit) {
		return scalar(Objects.requireNonNull(wit.definition()));
	}

	/**
	 * The Java type of a WIT scalar: an unsigned integer's the signed one of its width.
	 */
	static Primitive scalar(String wit) {
		return switch (wit) {
			case "bool" -> Primitive.BOOLEAN;
			case "s8", "u8" -> Primitive.BYTE;
			case "s16", "u16" -> Primitive.SHORT;
			case "s32", "u32", "char" -> Primitive.INT;
			case "s64", "u64" -> Primitive.LONG;
			case "f32" -> Primitive.FLOAT;
			case "f64" -> Primitive.DOUBLE;
			default -> throw new IllegalStateException(wit + " is no scalar");
		};
	}

	private static String boxText(Primitive primitive, boolean simple) {
		String box = box(primitive);
		return simple ? box.substring("java.lang.".length()) : box;
	}

	private static String box(Primitive primitive) {
		return switch (primitive) {
			case BOOLEAN -> "java.lang.Boolean";
			case BYTE -> "java.lang.Byte";
			case SHORT -> "java.lang.Short";
			case INT -> "java.lang.Integer";
			case LONG -> "java.lang.Long";
			case FLOAT -> "java.lang.Float";
			case DOUBLE -> "java.lang.Double";
			default -> throw new IllegalStateException(primitive + " has no box");
		};
	}

	/**
	 * The Java type a WIT type is, as Java source names it: fully qualified for the
	 * binding's source, by simple names for a message.
	 * @param owner the {@code @WitImport} interface, whose member types the named types
	 * are
	 * @param declared the type written for it, which a tuple's record is read from
	 */
	static String javaText(JavaClass owner, JavaBoundary.Type wit, boolean boxed, @Nullable JavaType declared,
			boolean simple) {
		return switch (wit.rep()) {
			case INT, BIGNUM_INT, FLOAT, BOOLEAN, CHARACTER ->
				boxed ? boxText(scalar(wit), simple) : scalar(wit).toString();
			case STRING -> simple ? "String" : "java.lang.String";
			case BYTE_STRING -> "byte[]";
			case LIST, NIL_OR_VALUE -> (simple ? "" : "java.util.") + (wit.rep() == Rep.LIST ? "List<" : "Optional<")
					+ javaText(owner, Objects.requireNonNull(wit.element()), true, argument(declared), simple) + ">";
			case TUPLE_LIST -> declared == null ? "a record of " + wit.parts().size() + " components"
					: simple ? declared.toString() : JavaAnnotations.typeText(declared);
			case KEYWORD_LIST -> (simple ? "Set<" : "java.util.Set<") + memberText(owner, wit, simple) + ">";
			case PLIST, TAGGED_LIST, KEYWORD, HANDLE -> memberText(owner, wit, simple);
			case STREAM_HANDLE -> (simple ? "WitStream<" : WIT_STREAM + "<")
					+ javaText(owner, Objects.requireNonNull(wit.element()), true, argument(declared), simple) + ">";
			case FUTURE_HANDLE -> {
				JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
				JavaBoundary.Type value = payload.rep() == Rep.RESULT ? payload.element() : payload;
				yield (simple ? "WitFuture<" : WIT_FUTURE + "<") + (value == null ? (simple ? "Void" : "java.lang.Void")
						: javaText(owner, value, true, argument(declared), simple)) + ">";
			}
			default -> wit.wit();
		};
	}

	private static @Nullable JavaType argument(@Nullable JavaType declared) {
		return declared instanceof ClassType type && type.arguments().size() == 1 ? type.arguments().get(0) : null;
	}

	/** The name of the member class a named type is. */
	private static String memberText(JavaClass owner, JavaBoundary.Type wit, boolean simple) {
		return (simple ? owner.simpleName() : owner.name) + "." + upperCamel(Objects.requireNonNull(wit.definition()));
	}

	// ---- the binding

	/**
	 * The provider a {@code ServiceLoader} of the interface finds on a WASM target: the
	 * binding, declared as generated Java source when first asked for.
	 * @return the binding class, or {@code null} for an interface no {@code @WitImport}
	 * names
	 */
	@Nullable JavaClass binding(JavaClass declaration) {
		JavaClass known = this.bindings.get(declaration);
		if (known != null) {
			return known;
		}
		Import imported = of(declaration);
		if (imported == null) {
			return null;
		}
		Unit unit = this.classes.unit(declaration);
		String simple = declaration.binaryName.substring(declaration.binaryName.lastIndexOf('.') + 1) + "$Wit";
		String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
		String text = new Writer(imported, name, simple, unit.packageName()).write();
		this.classes.declareGenerated(name, simple, text);
		JavaClass made = Objects.requireNonNull(this.classes.qualified(name));
		this.bindings.put(declaration, made);
		return made;
	}

	/** Starts a pass: no member reached yet. */
	void startPass() {
		this.reached.clear();
	}

	/**
	 * The directives binding the members the pass reached: a {@code rontolisp:wit-import}
	 * an interface, its {@code :names} table naming each member's bound function, its
	 * octets crossing as a {@code byte[]} ({@code :octets t}). On a {@code --no-wasi}
	 * module the interface crosses through the canonical ABI whatever members are reached
	 * ({@code :canonical t}), as on a component: the binding is written before the pass
	 * knows which, and it converts a {@code u32} the way that boundary takes it. An async
	 * func reached is blocked on ({@code :blocking t}), and a stream or a future read is
	 * read through an alias the directive adds ({@code :aliases}), its built-ins in the
	 * table.
	 */
	List<LispVal> directives() {
		List<LispVal> forms = new ArrayList<>();
		for (Map.Entry<JavaClass, Map<String, String>> entry : this.reached.entrySet()) {
			Import imported = Objects.requireNonNull(of(entry.getKey()));
			Map<String, String> reachedMembers = entry.getValue();
			List<LispVal> table = new ArrayList<>();
			for (String member : imported.members()) {
				String bound = reachedMembers.get(member);
				if (bound != null) {
					table.add(JavaForms.list(LispString.literal(member), LispString.literal(bound)));
				}
			}
			// the streams and futures the host answers, read through the built-ins of an
			// alias of their type
			List<LispVal> aliases = new ArrayList<>();
			for (Map.Entry<String, String> alias : this.aliases.getOrDefault(entry.getKey(), Map.of()).entrySet()) {
				boolean used = false;
				for (String op : List.of("read", "drop-readable")) {
					String bound = reachedMembers.get(alias.getKey() + "-" + op);
					if (bound != null) {
						table.add(JavaForms.list(LispString.literal(alias.getKey() + "-" + op),
								LispString.literal(bound)));
						used = true;
					}
				}
				if (used) {
					aliases
						.add(JavaForms.list(LispString.literal(alias.getKey()), LispString.literal(alias.getValue())));
				}
			}
			List<LispVal> directive = new ArrayList<>(
					List.of(new LispSymbol("RONTOLISP:WIT-IMPORT"), LispString.literal(imported.path()),
							new LispSymbol(":INTERFACE"), LispString.literal(imported.id()), new LispSymbol(":NAMES"),
							JavaForms.list(table), new LispSymbol(":OCTETS"), JavaForms.T));
			if (this.boundary.reactorModule()) {
				directive.add(new LispSymbol(":CANONICAL"));
				directive.add(JavaForms.T);
			}
			// an async func is a call that waits for its answer
			if (reachesAsync(imported, reachedMembers)) {
				directive.add(new LispSymbol(":BLOCKING"));
				directive.add(JavaForms.T);
			}
			if (!aliases.isEmpty()) {
				directive.add(new LispSymbol(":ALIASES"));
				directive.add(JavaForms.list(aliases));
			}
			forms.add(JavaForms.list(directive));
		}
		return forms;
	}

	// Whether the pass reached an async func of the interface, a resource's method
	// included.
	private static boolean reachesAsync(Import imported, Map<String, String> reached) {
		List<Bound> bound = new ArrayList<>(imported.functions());
		imported.resources().values().forEach(resource -> bound.addAll(resource.methods()));
		return bound.stream().anyMatch(each -> each.function().async() && reached.containsKey(each.function().name()));
	}

	/** Writes the Java source of one interface's binding. */
	private final class Writer {

		private final Import imported;

		/** The binding's name, as Java source names it. */
		private final String binding;

		private final String simple;

		private final @Nullable String packageName;

		private final StringBuilder methods = new StringBuilder();

		private final StringBuilder natives = new StringBuilder();

		private final StringBuilder converters = new StringBuilder();

		/** The converters written, by what they convert. */
		private final Map<String, String> written = new HashMap<>();

		/** The class each resource's handle is, by WIT name. */
		private final Map<String, String> handles = new LinkedHashMap<>();

		/**
		 * The classes of the streams and futures the host answers, read through the
		 * aliases the directive adds: their text, and each class by what it reads.
		 */
		private final StringBuilder asyncClasses = new StringBuilder();

		private final Map<String, String> asyncClassOf = new HashMap<>();

		Writer(Import imported, String binding, String simple, @Nullable String packageName) {
			this.imported = imported;
			this.binding = binding;
			this.simple = simple;
			this.packageName = packageName;
			int index = 0;
			for (String resource : imported.resources().keySet()) {
				this.handles.put(resource, binding + ".R" + index++);
			}
		}

		String write() {
			StringBuilder text = new StringBuilder();
			if (this.packageName != null) {
				text.append("package ").append(this.packageName).append(";\n");
			}
			text.append("public final class ")
				.append(this.simple)
				.append(" implements ")
				.append(this.imported.declaration().name)
				.append(" {\n    public ")
				.append(this.simple)
				.append("() {\n    }\n");
			for (Bound bound : this.imported.functions()) {
				method(bound, false);
			}
			StringBuilder resources = new StringBuilder();
			for (Resource resource : this.imported.resources().values()) {
				String handle = Objects.requireNonNull(this.handles.get(resource.name()));
				String own = handle.substring(handle.lastIndexOf('.') + 1);
				resources.append("    public static final class ")
					.append(own)
					.append(" implements ")
					.append(resource.declaration().name)
					.append(" {\n        int handle;\n        ")
					.append(own)
					.append("(int handle) {\n            this.handle = handle;\n        }\n")
					.append("        int live() {\n            if (this.handle < 0) {\n")
					.append("                throw new IllegalStateException(\"")
					.append(resource.name())
					.append(" of ")
					.append(this.imported.id())
					.append(" is closed\");\n            }\n            return this.handle;\n        }\n");
				// the resource's methods are written into its class
				StringBuilder members = new StringBuilder();
				this.methodsInto = members;
				for (Bound bound : resource.methods()) {
					method(bound, true);
				}
				this.methodsInto = this.methods;
				JavaBoundary.Function drop = resource.drop();
				if (drop != null) {
					// idempotent: the handle goes back once
					members.append("        public void close() {\n            if (this.handle >= 0) {\n")
						.append("                int held = this.handle;\n                this.handle = -1;\n")
						.append("                ")
						.append(this.binding)
						.append('.')
						.append(nativeFor(drop, 1, false))
						.append('(')
						.append(VALUES)
						.append(".outHandle(held));\n            }\n        }\n");
				}
				resources.append(members).append("    }\n");
			}
			text.append(this.methods)
				.append(resources)
				.append(this.asyncClasses)
				.append(this.natives)
				.append(this.converters)
				.append("}\n");
			return text.toString();
		}

		/** Where a method is written: the binding's own, or a resource's class. */
		private StringBuilder methodsInto = this.methods;

		/** A method of the interface or of a resource: the call of its member. */
		private void method(Bound bound, boolean resource) {
			JavaClass.Method method = bound.method();
			JavaBoundary.Function member = bound.function();
			String indent = resource ? "        " : "    ";
			StringBuilder out = this.methodsInto;
			out.append(indent)
				.append("public ")
				.append(method.returnType == Primitive.VOID ? "void" : JavaAnnotations.typeText(method.returnType))
				.append(' ')
				.append(method.name)
				.append('(');
			for (int i = 0; i < method.parameters.size(); i++) {
				out.append(i > 0 ? ", " : "")
					.append(JavaAnnotations.typeText(method.parameters.get(i)))
					.append(" p")
					.append(i);
			}
			out.append(") {\n");
			List<String> arguments = new ArrayList<>();
			List<JavaBoundary.Param> params = member.params();
			int first = 0;
			if (member.kind() == Kind.METHOD) {
				arguments.add(VALUES + ".outHandle(this.live())");
				first = 1;
			}
			for (int i = first; i < params.size(); i++) {
				arguments.add(out(params.get(i).type(), method.parameters.get(i - first), "p" + (i - first)));
			}
			JavaBoundary.Type result = member.result();
			boolean raw = result != null && result.rep() == Rep.RESULT;
			String call = this.binding + "." + nativeFor(member, params.size(), raw) + "("
					+ String.join(", ", arguments) + ")";
			String body = indent + "    ";
			if (result == null) {
				out.append(body).append(call).append(";\n");
			}
			else if (raw) {
				out.append(body)
					.append("Object answer = ")
					.append(call)
					.append(";\n")
					.append(body)
					.append("if (")
					.append(VALUES)
					.append(".failed(answer)) {\n")
					.append(body)
					.append("    Object error = ")
					.append(result.error() == null ? "null" : in(result.error(), null, VALUES + ".payload(answer)"))
					.append(";\n")
					.append(body)
					.append("    throw new ")
					.append(WIT_EXCEPTION)
					.append("(\"")
					.append(member.name())
					.append(" of ")
					.append(this.imported.id())
					.append(" answered its error arm\" + (error == null ? \"\" : \": \" + error), error);\n")
					.append(body)
					.append("}\n");
				if (result.element() != null) {
					out.append(body)
						.append("return ")
						.append(in(result.element(), method.returnType, VALUES + ".payload(answer)"))
						.append(";\n");
				}
			}
			else {
				out.append(body).append("return ").append(in(result, method.returnType, call)).append(";\n");
			}
			out.append(indent).append("}\n");
		}

		/**
		 * The native calling a member: a static method of the binding, answered by the
		 * function the directive binds the member to ({@code <name>%raw} for the envelope
		 * of a result), which the pass reaching it records.
		 */
		private String nativeFor(JavaBoundary.Function member, int arity, boolean raw) {
			return nativeFor(member.name(), arity, raw);
		}

		private String nativeFor(String member, int arity, boolean raw) {
			String name = "m" + this.nativeCount++;
			StringBuilder parameters = new StringBuilder();
			StringBuilder erased = new StringBuilder();
			for (int i = 0; i < arity; i++) {
				parameters.append(i > 0 ? ", " : "").append("Object a").append(i);
				erased.append(i > 0 ? "," : "").append("Object");
			}
			this.natives.append("    static native Object ").append(name).append('(').append(parameters).append(");\n");
			String bound = this.binding + "." + member;
			LispSymbol function = new LispSymbol(raw ? bound + "%raw" : bound);
			JavaClass declaration = this.imported.declaration();
			JavaWitImports.this.natives.put(this.binding + "." + name + "(" + erased + ")", (lowering, call) -> {
				JavaWitImports.this.reached.computeIfAbsent(declaration, key -> new LinkedHashMap<>())
					.put(member, bound);
				List<LispVal> operands = new ArrayList<>();
				for (JavaEmitter.Typed argument : call.arguments()) {
					operands.add(argument.form());
				}
				return JavaForms.form(function, operands);
			});
			return name;
		}

		private int nativeCount;

		// ---- the conversions

		/**
		 * An expression of the Java value a boundary value is.
		 * @param declared the type written for it, or {@code null} for an error arm's
		 * value, whose type the mapping names
		 */
		private String in(JavaBoundary.Type wit, @Nullable JavaType declared, String value) {
			String v = VALUES;
			return switch (wit.rep()) {
				case BOOLEAN -> v + ".inBoolean(" + value + ")";
				case INT, BIGNUM_INT -> switch (scalar(wit)) {
					case LONG -> v + ".inLong(" + value + ")";
					case INT -> "(int) " + v + ".inLong(" + value + ")";
					case SHORT -> "(short) " + v + ".inLong(" + value + ")";
					default -> "(byte) " + v + ".inLong(" + value + ")";
				};
				case FLOAT -> (scalar(wit) == Primitive.FLOAT ? "(float) " : "") + v + ".inDouble(" + value + ")";
				case CHARACTER -> v + ".inCodePoint(" + value + ")";
				case STRING -> v + ".inString(" + value + ")";
				case BYTE_STRING -> v + ".inBytes(" + value + ")";
				default -> converter("in", wit, declared) + "(" + value + ")";
			};
		}

		/** An expression of the boundary value a Java value is. */
		private String out(JavaBoundary.Type wit, @Nullable JavaType declared, String value) {
			String v = VALUES;
			return switch (wit.rep()) {
				case BOOLEAN -> v + ".outBoolean(" + value + ")";
				// an unsigned integer as the value its bits are: an import through the
				// canonical ABI (a component's, a --no-wasi module's) takes the value; a
				// Preview 1 module's takes a u32 as the s32 of its bits, which the int
				// holds, and neither has a value of a u64 past a long's range, which
				// traps
				// there
				case INT, BIGNUM_INT -> switch (Objects.requireNonNull(wit.definition())) {
					case "u8" -> v + ".outUnsigned(" + value + ", 8)";
					case "u16" -> v + ".outUnsigned(" + value + ", 16)";
					case "u32" ->
						JavaWitImports.this.boundary.component() || JavaWitImports.this.boundary.reactorModule()
								? v + ".outUnsigned(" + value + ", 32)" : v + ".outSigned(" + value + ")";
					default -> v + ".outSigned(" + value + ")";
				};
				case FLOAT -> v + ".outDouble(" + value + ")";
				case CHARACTER -> v + ".outCodePoint(" + value + ")";
				case STRING -> v + ".outString(" + value + ")";
				case BYTE_STRING -> v + ".outBytes(" + value + ")";
				default -> converter("out", wit, declared) + "(" + value + ")";
			};
		}

		/** The static method converting a value of a composite type, written once. */
		private String converter(String direction, JavaBoundary.Type wit, @Nullable JavaType declared) {
			String key = direction + " " + wit + " " + declared;
			String known = this.written.get(key);
			if (known != null) {
				return this.binding + "." + known;
			}
			String name = direction + this.written.size();
			this.written.put(key, name);
			String javaType = javaText(this.imported.declaration(), wit, false, declared, false);
			String v = VALUES;
			StringBuilder body = new StringBuilder();
			boolean in = direction.equals("in");
			body.append("    static ")
				.append(in ? javaType : "Object")
				.append(' ')
				.append(name)
				.append('(')
				.append(in ? "Object" : javaType)
				.append(" v) {\n");
			switch (wit.rep()) {
				case LIST -> {
					JavaBoundary.Type element = Objects.requireNonNull(wit.element());
					JavaType declaredElement = argument(declared);
					String elementText = javaText(this.imported.declaration(), element, true, declaredElement, false);
					if (in) {
						body.append("        java.util.ArrayList<")
							.append(elementText)
							.append("> out = new java.util.ArrayList<>();\n")
							.append("        for (Object p = v; p != null; p = ")
							.append(v)
							.append(".rest(p)) {\n            out.add(")
							.append(in(element, declaredElement, v + ".first(p)"))
							.append(");\n        }\n        return out;\n");
					}
					else {
						body.append("        Object out = null;\n        for (")
							.append(elementText)
							.append(" e : v) {\n            out = ")
							.append(v)
							.append(".cons(")
							.append(out(element, declaredElement, "e"))
							.append(", out);\n        }\n        return ")
							.append(v)
							.append(".reversed(out);\n");
					}
				}
				case NIL_OR_VALUE -> {
					JavaBoundary.Type element = Objects.requireNonNull(wit.element());
					JavaType declaredElement = argument(declared);
					if (in) {
						body.append("        return v == null ? java.util.Optional.empty() : java.util.Optional.of(")
							.append(in(element, declaredElement, "v"))
							.append(");\n");
					}
					else {
						body.append("        return v.isPresent() ? ")
							.append(out(element, declaredElement, "v.get()"))
							.append(" : null;\n");
					}
				}
				case TUPLE_LIST -> {
					JavaClass record = ((ClassType) Objects.requireNonNull(declared)).declaration();
					List<JavaClass.Field> components = components(record);
					if (in) {
						body.append("        Object p = v;\n");
						List<String> made = new ArrayList<>();
						for (int i = 0; i < components.size(); i++) {
							body.append("        Object e")
								.append(i)
								.append(" = ")
								.append(v)
								.append(".first(p);\n        p = ")
								.append(v)
								.append(".rest(p);\n");
							made.add(in(Objects.requireNonNull(wit.parts().get(i).type()), components.get(i).type,
									"e" + i));
						}
						body.append("        return new ")
							.append(record.name)
							.append('(')
							.append(String.join(", ", made))
							.append(");\n");
					}
					else {
						StringBuilder list = new StringBuilder("null");
						for (int i = components.size() - 1; i >= 0; i--) {
							list.insert(0,
									v + ".cons("
											+ out(Objects.requireNonNull(wit.parts().get(i).type()),
													components.get(i).type, "v." + components.get(i).name + "()")
											+ ", ")
								.append(')');
						}
						body.append("        return ").append(list).append(";\n");
					}
				}
				case PLIST -> {
					JavaClass record = memberClass(wit);
					Map<String, JavaBoundary.Part> fields = new LinkedHashMap<>();
					for (JavaBoundary.Part part : wit.parts()) {
						fields.put(lowerCamel(part.label()), part);
					}
					if (in) {
						List<String> made = new ArrayList<>();
						for (JavaClass.Field component : components(record)) {
							JavaBoundary.Part field = Objects.requireNonNull(fields.get(component.name));
							made.add(in(Objects.requireNonNull(field.type()), component.type,
									v + ".field(v, \"" + field.label() + "\")"));
						}
						body.append("        return new ")
							.append(record.name)
							.append('(')
							.append(String.join(", ", made))
							.append(");\n");
					}
					else {
						Map<String, JavaClass.Field> byName = new HashMap<>();
						components(record).forEach(component -> byName.put(component.name, component));
						StringBuilder list = new StringBuilder("null");
						List<JavaBoundary.Part> parts = wit.parts();
						for (int i = parts.size() - 1; i >= 0; i--) {
							JavaBoundary.Part field = parts.get(i);
							JavaClass.Field component = Objects.requireNonNull(byName.get(lowerCamel(field.label())));
							list.insert(0,
									v + ".cons(" + v + ".label(\"" + field.label() + "\"), " + v + ".cons("
											+ out(Objects.requireNonNull(field.type()), component.type,
													"v." + component.name + "()")
											+ ", ")
								.append("))");
						}
						body.append("        return ").append(list).append(";\n");
					}
				}
				case TAGGED_LIST -> {
					JavaClass sealed = memberClass(wit);
					if (in) {
						body.append("        Object tag = ").append(v).append(".tag(v);\n");
					}
					for (JavaBoundary.Part part : wit.parts()) {
						JavaClass record = caseClass(sealed, part.label());
						JavaBoundary.Type payload = part.type();
						if (in) {
							body.append("        if (")
								.append(v)
								.append(".is(tag, \"")
								.append(part.label())
								.append("\")) {\n            return new ")
								.append(record.name)
								.append('(');
							if (payload != null) {
								body.append(in(payload, components(record).get(0).type, v + ".payload(v)"));
							}
							body.append(");\n        }\n");
						}
						else {
							body.append("        if (v instanceof ")
								.append(record.name)
								.append(") {\n            return ");
							if (payload == null) {
								body.append(v).append(".label(\"").append(part.label()).append("\")");
							}
							else {
								JavaClass.Field component = components(record).get(0);
								body.append(v)
									.append(".cons(")
									.append(v)
									.append(".label(\"")
									.append(part.label())
									.append("\"), ")
									.append(out(payload, component.type,
											"((" + record.name + ") v)." + component.name + "()"))
									.append(')');
							}
							body.append(";\n        }\n");
						}
					}
					body.append(in ? "        throw new IllegalStateException(\"no case of " + wit.definition() + " of "
							+ this.imported.id() + "\");\n" : "        throw new NullPointerException();\n");
				}
				case KEYWORD -> {
					JavaClass named = memberClass(wit);
					for (JavaBoundary.Part part : wit.parts()) {
						if (in) {
							body.append("        if (")
								.append(v)
								.append(".is(v, \"")
								.append(part.label())
								.append("\")) {\n            return ")
								.append(named.name)
								.append('.')
								.append(constant(part.label()))
								.append(";\n        }\n");
						}
						else {
							body.append("        if (v == ")
								.append(named.name)
								.append('.')
								.append(constant(part.label()))
								.append(") {\n            return ")
								.append(v)
								.append(".label(\"")
								.append(part.label())
								.append("\");\n        }\n");
						}
					}
					body.append(in ? "        throw new IllegalStateException(\"no case of " + wit.definition() + " of "
							+ this.imported.id() + "\");\n" : "        throw new NullPointerException();\n");
				}
				case KEYWORD_LIST -> {
					JavaClass named = memberClass(wit);
					if (in) {
						body.append("        java.util.Set<")
							.append(named.name)
							.append("> out = java.util.EnumSet.noneOf(")
							.append(named.name)
							.append(".class);\n        for (Object p = v; p != null; p = ")
							.append(v)
							.append(".rest(p)) {\n            Object k = ")
							.append(v)
							.append(".first(p);\n");
						for (JavaBoundary.Part part : wit.parts()) {
							body.append("            if (")
								.append(v)
								.append(".is(k, \"")
								.append(part.label())
								.append("\")) {\n                out.add(")
								.append(named.name)
								.append('.')
								.append(constant(part.label()))
								.append(");\n            }\n");
						}
						body.append("        }\n        return out;\n");
					}
					else {
						body.append("        Object out = null;\n");
						List<JavaBoundary.Part> parts = wit.parts();
						for (int i = parts.size() - 1; i >= 0; i--) {
							body.append("        if (v.contains(")
								.append(named.name)
								.append('.')
								.append(constant(parts.get(i).label()))
								.append(")) {\n            out = ")
								.append(v)
								.append(".cons(")
								.append(v)
								.append(".label(\"")
								.append(parts.get(i).label())
								.append("\"), out);\n        }\n");
						}
						body.append("        return out;\n");
					}
				}
				case HANDLE -> {
					String handle = Objects.requireNonNull(this.handles.get(wit.definition()));
					if (in) {
						body.append("        return new ")
							.append(handle)
							.append('(')
							.append(v)
							.append(".inHandle(v));\n");
					}
					else {
						body.append("        if (!(v instanceof ")
							.append(handle)
							.append(")) {\n            throw new IllegalArgumentException(\"a ")
							.append(wit.definition())
							.append(" of ")
							.append(this.imported.id())
							.append(" the host did not make\");\n        }\n        ")
							.append(handle)
							.append(" h = (")
							.append(handle)
							.append(") v;\n        int held = h.live();\n");
						if (!wit.borrow()) {
							// an own handle passes to the callee
							body.append("        h.handle = -1;\n");
						}
						body.append("        return ").append(v).append(".outHandle(held);\n");
					}
				}
				case STREAM_HANDLE, FUTURE_HANDLE -> {
					if (!in) {
						throw new IllegalStateException(wit.wit() + " is no value the program hands the host");
					}
					body.append("        return new ")
						.append(asyncClass(wit, declared))
						.append('(')
						.append(v)
						.append(".inHandle(v));\n");
				}
				default -> throw new IllegalStateException(wit.rep() + " has no converter");
			}
			body.append("    }\n");
			this.converters.append(body);
			return this.binding + "." + name;
		}

		/**
		 * The class of a stream or a future the host answers, written once: its reads and
		 * its release the built-ins of an alias the directive adds for its type.
		 */
		private String asyncClass(JavaBoundary.Type wit, @Nullable JavaType declared) {
			boolean stream = wit.rep() == Rep.STREAM_HANDLE;
			String key = wit.rep() + " " + wit + " " + declared;
			String known = this.asyncClassOf.get(key);
			if (known != null) {
				return known;
			}
			String simpleName = (stream ? "S" : "F") + this.asyncClassOf.size();
			String name = this.binding + "." + simpleName;
			this.asyncClassOf.put(key, name);
			Map<String, String> aliases = JavaWitImports.this.aliases.computeIfAbsent(this.imported.declaration(),
					declaration -> new LinkedHashMap<>());
			String alias = aliases.entrySet()
				.stream()
				.filter(entry -> entry.getValue().equals(wit.wit()))
				.map(Map.Entry::getKey)
				.findFirst()
				.orElse(null);
			if (alias == null) {
				alias = (stream ? "java-stream-" : "java-future-") + aliases.size();
				aliases.put(alias, wit.wit());
			}
			String read = this.binding + "." + nativeFor(alias + "-read", stream ? 2 : 1, false);
			String drop = this.binding + "." + nativeFor(alias + "-drop-readable", 1, false);
			String javaType = javaText(this.imported.declaration(), wit, false, declared, false);
			String what = wit.wit() + " of " + this.imported.id();
			String v = VALUES;
			JavaType argument = argument(declared);
			StringBuilder text = new StringBuilder();
			text.append("    public static final class ")
				.append(simpleName)
				.append(" implements ")
				.append(javaType)
				.append(" {\n        int handle;\n");
			JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
			JavaBoundary.Type value = stream || payload.rep() != Rep.RESULT ? payload : payload.element();
			String valueText = value == null ? "java.lang.Void"
					: javaText(this.imported.declaration(), value, true, argument, false);
			if (!stream) {
				text.append("        boolean read;\n        ")
					.append(valueText)
					.append(" value;\n        RuntimeException failure;\n");
			}
			text.append("        ")
				.append(simpleName)
				.append("(int handle) {\n            this.handle = handle;\n        }\n");
			if (stream) {
				text.append("        public java.util.List<")
					.append(valueText)
					.append("> read(int max) {\n            if (max < 1) {\n")
					.append("                throw new IllegalArgumentException(\"A read of \" + max + \" values\");\n")
					.append("            }\n            if (this.handle < 0) {\n")
					.append("                throw new IllegalStateException(\"")
					.append(what)
					.append(" is closed\");\n            }\n            java.util.ArrayList<")
					.append(valueText)
					.append("> out = new java.util.ArrayList<>();\n            for (Object p = ")
					.append(read)
					.append('(')
					.append(v)
					.append(".outHandle(this.handle), ")
					.append(v)
					.append(".outSigned(max)); p != null; p = ")
					.append(v)
					.append(".rest(p)) {\n                out.add(")
					.append(in(payload, argument, v + ".first(p)"))
					.append(");\n            }\n            return out;\n        }\n");
			}
			else {
				boolean result = payload.rep() == Rep.RESULT;
				text.append("        public ")
					.append(valueText)
					.append(" get() {\n            if (!this.read) {\n                if (this.handle < 0) {\n")
					.append("                    throw new IllegalStateException(\"")
					.append(what)
					.append(" is closed\");\n                }\n                int held = this.handle;\n")
					.append("                this.handle = -1;\n                Object answer = ")
					.append(read)
					.append('(')
					.append(v)
					.append(".outHandle(held));\n                ")
					.append(drop)
					.append('(')
					.append(v)
					.append(".outHandle(held));\n                this.read = true;\n");
				if (result) {
					// the read of a future its writer dropped answers nil, which no
					// result's envelope is
					JavaBoundary.Type error = payload.error();
					text.append("                if (answer == null) {\n")
						.append("                    this.failure = new IllegalStateException(\"The writer of ")
						.append(what)
						.append(" went without sending a value\");\n                }\n                else if (")
						.append(v)
						.append(".failed(answer)) {\n                    Object error = ")
						.append(error == null ? "null" : in(error, null, v + ".payload(answer)"))
						.append(";\n                    this.failure = new ")
						.append(WIT_EXCEPTION)
						.append("(\"")
						.append(what)
						.append(" answered its error arm\" + (error == null ? \"\" : \": \" + error), error);\n")
						.append("                }\n");
					if (value != null) {
						text.append("                else {\n                    this.value = ")
							.append(in(value, argument, v + ".payload(answer)"))
							.append(";\n                }\n");
					}
				}
				else {
					text.append("                if (answer == null) {\n")
						.append("                    this.failure = new IllegalStateException(\"The writer of ")
						.append(what)
						.append(" went without sending a value\");\n                }\n")
						.append("                else {\n                    this.value = ")
						.append(in(payload, argument, "answer"))
						.append(";\n                }\n");
				}
				text.append("            }\n            if (this.failure != null) {\n")
					.append("                throw this.failure;\n            }\n            return this.value;\n")
					.append("        }\n");
			}
			text.append("        public void close() {\n            if (this.handle >= 0) {\n")
				.append("                int held = this.handle;\n                this.handle = -1;\n                ")
				.append(drop)
				.append('(')
				.append(v)
				.append(".outHandle(held));\n            }\n        }\n    }\n");
			this.asyncClasses.append(text);
			return name;
		}

		/** The interface's member class a named type is. */
		private JavaClass memberClass(JavaBoundary.Type wit) {
			return Objects.requireNonNull(
					this.imported.declaration().members.get(upperCamel(Objects.requireNonNull(wit.definition()))));
		}

		/** A sealed interface's permitted record of a case's name. */
		private JavaClass caseClass(JavaClass sealed, String label) {
			for (JavaClass subclass : sealed.permits) {
				if (subclass.simpleName().equals(upperCamel(label))) {
					return subclass;
				}
			}
			throw new IllegalStateException("no case " + label + " of " + sealed);
		}

	}

}
