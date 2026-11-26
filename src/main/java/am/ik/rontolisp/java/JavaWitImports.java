package am.ik.rontolisp.java;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
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
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The program's {@code @WitImport} interfaces: a Java interface standing for a WIT
 * interface, its abstract methods the WIT functions the program calls
 * ({@code .kb/java-frontend.md}, "WIT imports"). Each is checked against the WIT on every
 * target, member by member and type by type, against the one mapping of a WIT type onto a
 * Java type ({@link JavaWitTypes}, {@code @WitExport}'s too); the WIT is read through the
 * host boundary ({@link JavaBoundary}), so a member is the one the
 * {@code rontolisp:wit-import} binding it names.
 *
 * <p>
 * What implements the interface is found with {@code ServiceLoader}: the program's own
 * provider on the interpreter, the JVM and a JDK, the host on a WASM target
 * ({@link JavaBoundary#hostImports}). There the provider is a class of generated Java
 * source, the BINDING, lowered as far as the program reaches it like an annotation's
 * class: each method converts its arguments to the boundary's values (the settled Common
 * Lisp tier, {@code rontolisp.internal.Wit}), calls the member the directive binds
 * through a native of its own, and converts the answer back -- a {@code result} a
 * {@code WitResult}, or, answered by a method declaring its ok arm, the error arm a
 * {@code WitException}. The directive names the members the program's last pass reached,
 * so a component imports what the program calls.
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

	/** The type a {@code result} is as a value. */
	static final String WIT_RESULT = "am.ik.rontolisp.wit.WitResult";

	/** What a WIT {@code stream<T>} the host answers is. */
	static final String WIT_STREAM = "am.ik.rontolisp.wit.WitStream";

	/** What a WIT {@code future<T>} the host answers is. */
	static final String WIT_FUTURE = "am.ik.rontolisp.wit.WitFuture";

	/** The library class of the boundary's values. */
	private static final String VALUES = JavaWitTypes.VALUES;

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

	/**
	 * What one {@code @WitImport} interface is checked by: its types against the mapping
	 * ({@link JavaWitTypes.Table}), its methods and its resources' against the members.
	 */
	private final class Checker extends JavaWitTypes.Table {

		private final JavaClass declaration;

		private final JavaBoundary.WitInterface wit;

		private final Source source;

		/** The resources the interface defines, which a handle may be of. */
		private final Map<String, JavaBoundary.Function> drops = new LinkedHashMap<>();

		/** The resources met, in order. */
		private final Map<String, Resource> resources = new LinkedHashMap<>();

		Checker(JavaClass declaration, JavaBoundary.WitInterface wit, Source source) {
			super(declaration, wit.id(), "the @WitImport interface", source);
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
				String async = asyncIn(param.type());
				if (async != null) {
					throw at(method, what + ": its parameter " + param.name() + " carries " + async
							+ ", which the Java subset carries only where the host answers one");
				}
				expect(param.type(), method.parameters.get(i), false, method, what + ": its parameter " + param.name());
			}
			expectAnswer(member.result(), method, what);
			return new Bound(method, member);
		}

		/** A resource's interface checked: each abstract method a method of it. */
		@Override
		void resource(String name, JavaClass named) {
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

	/**
	 * Whether a method answering a {@code result} declares its ok arm, the error arm
	 * thrown as a {@code WitException}, rather than the {@code WitResult} of the whole:
	 * the binding follows the declaration. Told by how deep the declared
	 * {@code WitResult}s nest along the ok arms against the WIT's {@code result}s, so a
	 * {@code result<result<A, B>, E>} is either {@code WitResult<WitResult<A, B>, E>} or
	 * {@code WitResult<A, B>} thrown.
	 */
	static boolean thrown(JavaBoundary.Type result, JavaType declared) {
		if (result.rep() != Rep.RESULT) {
			return false;
		}
		int wit = 0;
		for (JavaBoundary.Type arm = result; arm != null && arm.rep() == Rep.RESULT; arm = arm.element()) {
			wit++;
		}
		int written = 0;
		for (JavaType arm = declared; arm instanceof ClassType type && type.declaration().name.equals(WIT_RESULT)
				&& type.arguments().size() == 2; arm = type.arguments().get(0)) {
			written++;
		}
		return written != wit;
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
	private final class Writer extends JavaWitTypes.Converters {

		private final Import imported;

		private final String simple;

		private final @Nullable String packageName;

		private final StringBuilder methods = new StringBuilder();

		private final StringBuilder natives = new StringBuilder();

		/** The class each resource's handle is, by WIT name. */
		private final Map<String, String> handles = new LinkedHashMap<>();

		/**
		 * The classes of the streams and futures the host answers, read through the
		 * aliases the directive adds: their text, and each class by what it reads.
		 */
		private final StringBuilder asyncClasses = new StringBuilder();

		private final Map<String, String> asyncClassOf = new HashMap<>();

		Writer(Import imported, String binding, String simple, @Nullable String packageName) {
			// an import through the canonical ABI (a component's, a --no-wasi module's)
			// takes a u32 as its value
			super(imported.declaration(), imported.id(), binding,
					JavaWitImports.this.boundary.component() || JavaWitImports.this.boundary.reactorModule());
			this.imported = imported;
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

		@Override
		String handleClass(String resource) {
			return Objects.requireNonNull(this.handles.get(resource));
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
			else if (thrown(result, method.returnType)) {
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

		/**
		 * The class of a stream or a future the host answers, written once: its reads and
		 * its release the built-ins of an alias the directive adds for its type.
		 */
		@Override
		String asyncClass(JavaBoundary.Type wit, @Nullable JavaType declared) {
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
			String javaType = JavaWitTypes.javaText(this.imported.declaration(), wit, false, declared, false);
			String what = wit.wit() + " of " + this.imported.id();
			String v = VALUES;
			JavaType argument = JavaWitTypes.argument(declared);
			StringBuilder text = new StringBuilder();
			text.append("    public static final class ")
				.append(simpleName)
				.append(" implements ")
				.append(javaType)
				.append(" {\n        int handle;\n");
			JavaBoundary.Type payload = Objects.requireNonNull(wit.element());
			JavaBoundary.Type value = stream || payload.rep() != Rep.RESULT ? payload : payload.element();
			String valueText = value == null ? "java.lang.Void"
					: JavaWitTypes.javaText(this.imported.declaration(), value, true, argument, false);
			if (!stream) {
				// what get() throws is made where it throws: the making of what a throw
				// throws decides no loop's turn (JavaResumable)
				text.append(
						"        boolean read;\n        boolean gone;\n        boolean failed;\n        Object error;\n        ")
					.append(valueText)
					.append(" value;\n");
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
				// the read of a future its writer dropped answers nil, which no result's
				// envelope is and no value the checker lets a future carry
				text.append("                if (answer == null) {\n                    this.gone = true;\n")
					.append("                }\n");
				if (result) {
					JavaBoundary.Type error = payload.error();
					text.append("                else if (")
						.append(v)
						.append(".failed(answer)) {\n                    this.failed = true;\n")
						.append("                    this.error = ")
						.append(error == null ? "null" : in(error, null, v + ".payload(answer)"))
						.append(";\n                }\n");
					if (value != null) {
						text.append("                else {\n                    this.value = ")
							.append(in(value, argument, v + ".payload(answer)"))
							.append(";\n                }\n");
					}
				}
				else {
					text.append("                else {\n                    this.value = ")
						.append(in(payload, argument, "answer"))
						.append(";\n                }\n");
				}
				text.append("            }\n            if (this.gone) {\n")
					.append("                throw new IllegalStateException(\"The writer of ")
					.append(what)
					.append(" went without sending a value\");\n            }\n            if (this.failed) {\n")
					.append("                throw new ")
					.append(WIT_EXCEPTION)
					.append("(\"")
					.append(what)
					.append(" answered its error arm\" + (this.error == null ? \"\" : \": \" + this.error), ")
					.append("this.error);\n            }\n            return this.value;\n        }\n");
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

	}

}
