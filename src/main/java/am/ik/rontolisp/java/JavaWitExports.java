package am.ik.rontolisp.java;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaBoundary.Rep;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaClasses.Unit;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The program's {@code @WitExport} classes: a class standing for a WIT world, each
 * function the world exports a static method of it and each interface the world exports a
 * static member class of it, whose static methods are the interface's functions
 * ({@code .kb/java-frontend.md}, "WIT exports"). Each is checked against its world on
 * every target, export by export and type by type, against the mapping of a WIT type onto
 * a Java type {@code @WitImport} has, of the types a world's export carries; the WIT is
 * read through the host boundary ({@link JavaBoundary}), so an export is the one the
 * {@code rontolisp:wit-export} the lowering emits checks.
 *
 * <p>
 * A program whose entry class has no {@code main} starts at the {@code @WitExport}
 * classes of its source tree instead ({@link #entries}): its worlds' exports are its
 * entry points. On a component target the lowering emits a {@code rontolisp:wit-export}
 * of each world, its {@code :names} table naming the method implementing each export, or
 * the conversion around it where an unsigned integer crosses, and {@code :reactor t}: the
 * component lifts no {@code run}, and the first export call runs the program's static
 * initialization. Everywhere else such a program is refused. An {@code async func} is a
 * method answering its value, as any other: the directive lifts it async, so its call's
 * task may wait where the method blocks. A program with a {@code main} is a command: its
 * {@code @WitExport} classes are checked, and exported nowhere.
 */
final class JavaWitExports {

	/** The annotation, in the library a program names it from. */
	static final String WIT_EXPORT = "am.ik.rontolisp.wit.WitExport";

	private final JavaClasses classes;

	/** Each class asked about: its world, or none for one no {@code @WitExport} marks. */
	private final Map<JavaClass, Optional<World>> worlds = new IdentityHashMap<>();

	/**
	 * Whether the program's entry points are its {@code @WitExport} classes: its entry
	 * class has no {@code main}, and its source tree holds some.
	 */
	boolean entries;

	JavaWitExports(JavaClasses classes) {
		this.classes = classes;
	}

	/**
	 * A Java class standing for a WIT world, checked.
	 *
	 * @param declaration the class
	 * @param name the world's name
	 * @param path the WIT as the directive names it
	 * @param exports its exports, in world order, each by the Java method that is it
	 */
	record World(JavaClass declaration, String name, String path, List<Bound> exports) {
	}

	/**
	 * A static method and the export it is.
	 *
	 * @param export the export
	 * @param method the method
	 */
	record Bound(JavaBoundary.Export export, JavaClass.Method method) {
	}

	// ---- the annotation

	/**
	 * The world a class implements, checked, or {@code null} when no {@code @WitExport}
	 * marks it.
	 * @throws LispReadException where the annotation, the WIT or a declaration is wrong,
	 * at the declaration
	 */
	@Nullable World of(JavaClass declaration) {
		Optional<World> known = this.worlds.get(declaration);
		if (known != null) {
			return known.orElse(null);
		}
		JavaSyntax.Annotation written = annotation(declaration);
		if (written == null) {
			this.worlds.put(declaration, Optional.empty());
			return null;
		}
		Unit unit = this.classes.unit(declaration);
		Source source = unit.source();
		if (declaration.isLocal || declaration.isAnnotation) {
			throw JavaClasses.error(source, written.pos(),
					"@WitExport annotates a class whose static methods are the " + "functions of the WIT world; "
							+ declaration.simpleName() + " is "
							+ (declaration.isLocal ? "a local class" : "an annotation type"));
		}
		String world = JavaWitImports.literal(written, "WitExport", "value", unit);
		String wit = JavaWitImports.literal(written, "WitExport", "wit", unit);
		// found as Class.getResource finds a name: below the package of the class, or
		// from the top of a root after a '/', by the loader of the class
		String packageName = this.classes.packageOf(declaration);
		String name = JavaFiles.below(wit.startsWith("/") ? wit.substring(1)
				: packageName == null || packageName.isEmpty() ? wit : packageName.replace('.', '/') + "/" + wit);
		if (name == null) {
			throw JavaClasses.error(source, written.pos(),
					"@WitExport: cannot read WIT " + wit + ": it names a resource above the top of a root");
		}
		JavaBoundary.WitWorld described;
		try {
			described = this.classes.wit.boundary.exportWorld(wit, this.classes.resourcePaths(name, true), world);
		}
		catch (LispReadException ex) {
			throw JavaClasses.error(source, written.pos(), "@WitExport: " + ex.getMessage());
		}
		if (described == null) {
			throw JavaClasses.error(source, written.pos(), "@WitExport: cannot read WIT " + wit
					+ ": no file of the source tree and no root of the source path holds " + name);
		}
		World made = new Checker(declaration, described, source).check();
		this.worlds.put(declaration, Optional.of(made));
		return made;
	}

	/**
	 * The classes of the program {@code @WitExport} marks, in the table's order: a
	 * class's of the program's own files, never one of a library.
	 */
	List<JavaClass> annotated() {
		List<JavaClass> marked = new ArrayList<>();
		for (JavaClass declaration : List.copyOf(this.classes.all())) {
			if (annotation(declaration) != null) {
				marked.add(declaration);
			}
		}
		return marked;
	}

	/** Checks every {@code @WitExport} class the program has. */
	void checkAll() {
		for (JavaClass declaration : annotated()) {
			of(declaration);
		}
	}

	/** The {@code @WitExport} written on a class of the program, or {@code null}. */
	private JavaSyntax.@Nullable Annotation annotation(JavaClass declaration) {
		JavaSyntax.ClassDeclaration written = declaration.declaration;
		if (written == null || declaration.intrinsic || declaration.source == null || declaration.source.partial()) {
			return null;
		}
		Unit unit = null;
		for (JavaSyntax.Annotation annotation : written.annotations()) {
			String name = annotation.name();
			if (!name.equals("WitExport") && !name.equals(WIT_EXPORT)) {
				continue;
			}
			unit = unit == null ? this.classes.unit(declaration) : unit;
			JavaClass type = this.classes.annotationType(annotation, declaration, unit);
			if (type != null && type.name.equals(WIT_EXPORT)) {
				return annotation;
			}
		}
		return null;
	}

	// ---- the check

	/** What one {@code @WitExport} class is checked by. */
	private final class Checker {

		private final JavaClass declaration;

		private final JavaBoundary.WitWorld world;

		private final Source source;

		Checker(JavaClass declaration, JavaBoundary.WitWorld world, Source source) {
			this.declaration = declaration;
			this.world = world;
			this.source = source;
		}

		World check() {
			List<Bound> bound = new ArrayList<>();
			for (JavaBoundary.Export export : this.world.exports()) {
				bound.add(new Bound(export, method(holder(export), export)));
			}
			return new World(this.declaration, this.world.name(), this.world.path(), List.copyOf(bound));
		}

		/** The class an export's method is of: the class, or an interface's member. */
		private JavaClass holder(JavaBoundary.Export export) {
			String iface = export.iface();
			if (iface == null) {
				return this.declaration;
			}
			String name = JavaWitImports.upperCamel(interfaceName(iface));
			JavaClass member = this.declaration.members.get(name);
			if (member == null) {
				throw at(this.declaration,
						this.declaration.simpleName() + " declares no member class " + name
								+ ", which is the interface " + iface + " the world " + this.world.name() + " exports ("
								+ export.at() + "): a static member class whose static methods are its functions");
			}
			return member;
		}

		/** The static method implementing an export, checked against it. */
		private JavaClass.Method method(JavaClass holder, JavaBoundary.Export export) {
			String name = JavaWitImports.lowerCamel(export.name());
			String what = name + " (" + shown(export) + ", " + export.at() + ")";
			List<JavaClass.Method> named = new ArrayList<>();
			for (JavaClass.Method method : holder.methods) {
				if (method.name.equals(name) && !method.isConstructor()) {
					named.add(method);
				}
			}
			List<JavaClass.Method> statics = named.stream().filter(method -> method.isStatic).toList();
			if (statics.isEmpty()) {
				if (!named.isEmpty()) {
					throw at(named.get(0), what + " is a static method in Java, which the host calls with no instance");
				}
				throw at(holder, holder.simpleName() + " declares no method " + name + " for " + shown(export) + " ("
						+ export.at() + "): static " + signature(name, export));
			}
			for (JavaClass.Method method : statics) {
				if (matches(method, export)) {
					return method;
				}
			}
			// the one a reader means: of the arity, else the first
			JavaClass.Method meant = statics.stream()
				.filter(method -> method.parameters.size() == export.params().size())
				.findFirst()
				.orElse(statics.get(0));
			List<JavaBoundary.Param> params = export.params();
			if (meant.parameters.size() != params.size()) {
				throw at(meant, what + " takes " + params.size() + " argument" + (params.size() == 1 ? "" : "s")
						+ ", not " + meant.parameters.size());
			}
			for (int i = 0; i < params.size(); i++) {
				JavaBoundary.Param param = params.get(i);
				if (!matches(param.type(), meant.parameters.get(i))) {
					throw at(meant, what + ": its parameter " + param.name() + " is " + param.type().wit()
							+ ", which is " + javaText(param.type()) + " in Java, not " + meant.parameters.get(i));
				}
			}
			JavaBoundary.Type result = export.result();
			if (result == null) {
				throw at(meant, what + " answers nothing, so it is void, not " + meant.returnType);
			}
			throw at(meant, what + ": its result is " + result.wit() + ", which is " + javaText(result)
					+ " in Java, not " + meant.returnType);
		}

		private LispReadException at(JavaClass.Method method, String message) {
			JavaSyntax.Method written = method.declaration;
			if (written != null && method.owner.source != null) {
				return JavaClasses.error(method.owner.source, written.pos(), message);
			}
			return at(method.owner, message);
		}

		private LispReadException at(JavaClass named, String message) {
			JavaSyntax.ClassDeclaration written = named.declaration;
			return JavaClasses.error(named.source == null ? this.source : named.source,
					written == null ? 0 : written.pos(), message);
		}

		/** An export as a message names it: its label, and its interface or world. */
		private String shown(JavaBoundary.Export export) {
			return export.name() + " of " + (export.iface() == null ? "world " + this.world.name() : export.iface());
		}

	}

	/** Whether a method's parameters and result are those of an export. */
	private static boolean matches(JavaClass.Method method, JavaBoundary.Export export) {
		if (method.parameters.size() != export.params().size()) {
			return false;
		}
		for (int i = 0; i < method.parameters.size(); i++) {
			if (!matches(export.params().get(i).type(), method.parameters.get(i))) {
				return false;
			}
		}
		JavaBoundary.Type result = export.result();
		return result == null ? method.returnType == Primitive.VOID : matches(result, method.returnType);
	}

	/** Whether a declared type is the one a WIT type maps onto. */
	private static boolean matches(JavaBoundary.Type wit, JavaType declared) {
		return wit.rep() == Rep.STRING ? declared instanceof ClassType named
				&& named.declaration().name.equals("java.lang.String") && named.arguments().isEmpty()
				: declared == JavaWitImports.scalar(wit.wit());
	}

	/** The Java type a WIT type crossing an export is, as a message names it. */
	private static String javaText(JavaBoundary.Type wit) {
		return wit.rep() == Rep.STRING ? "String" : JavaWitImports.scalar(wit.wit()).toString();
	}

	/** The declaration an export's method has, as a message writes it. */
	private static String signature(String name, JavaBoundary.Export export) {
		List<String> params = new ArrayList<>();
		for (JavaBoundary.Param param : export.params()) {
			params.add(javaText(param.type()) + " " + JavaWitImports.lowerCamel(param.name()));
		}
		JavaBoundary.Type result = export.result();
		return (result == null ? "void" : javaText(result)) + " " + name + "(" + String.join(", ", params) + ")";
	}

	/**
	 * The plain name of an exported interface: {@code add} of
	 * {@code docs:adder/add@0.1.0}, an inline one's own.
	 */
	private static String interfaceName(String iface) {
		String plain = iface.substring(iface.lastIndexOf('/') + 1);
		int version = plain.indexOf('@');
		return version < 0 ? plain : plain.substring(0, version);
	}

	// ---- the lowering

	/**
	 * The forms exporting the worlds of a program whose entry points they are: ahead of
	 * each world's {@code rontolisp:wit-export}, the conversion of each export an
	 * unsigned integer crosses -- the boundary's value is the number, the Java value its
	 * bits in the signed type of its width -- and the directive naming each export's
	 * function, the program a reactor.
	 */
	List<LispVal> directives() {
		List<LispVal> forms = new ArrayList<>();
		for (JavaClass declaration : annotated()) {
			World world = Objects.requireNonNull(of(declaration));
			List<LispVal> table = new ArrayList<>();
			for (Bound bound : world.exports()) {
				LispSymbol function = JavaNames.function(bound.method());
				LispSymbol converted = conversion(bound, function, forms);
				table.add(JavaForms.list(JavaForms.string(bound.export().key()),
						JavaForms.string((converted == null ? function : converted).name())));
			}
			forms.add(JavaForms.list(new LispSymbol("RONTOLISP:WIT-EXPORT"), JavaForms.string(world.path()),
					new LispSymbol(":WORLD"), JavaForms.string(world.name()), new LispSymbol(":NAMES"),
					JavaForms.list(table), new LispSymbol(":REACTOR"), JavaForms.T));
		}
		return forms;
	}

	/**
	 * The function converting an export's unsigned integers around its method, added to
	 * the forms, or {@code null} where nothing needs converting.
	 */
	private static @Nullable LispSymbol conversion(Bound bound, LispSymbol function, List<LispVal> forms) {
		JavaBoundary.Export export = bound.export();
		List<LispVal> parameters = new ArrayList<>();
		List<LispVal> arguments = new ArrayList<>();
		boolean converts = false;
		for (int i = 0; i < export.params().size(); i++) {
			LispSymbol parameter = new LispSymbol("p" + i);
			parameters.add(parameter);
			int bits = unsignedBits(export.params().get(i).type());
			arguments.add(bits == 0 ? parameter : JavaForms.signed(parameter, bits));
			converts |= bits != 0;
		}
		LispVal call = JavaForms.form(function, arguments);
		JavaBoundary.Type result = export.result();
		int bits = result == null ? 0 : unsignedBits(result);
		if (bits != 0) {
			call = JavaForms.call("logand", call,
					JavaForms.integer(BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE)));
		}
		if (!converts && bits == 0) {
			return null;
		}
		LispSymbol converted = new LispSymbol(function.name() + "%wit");
		forms.add(JavaForms.list(JavaForms.cl("defun"), converted, JavaForms.list(parameters), call));
		return converted;
	}

	/** The width of an unsigned integer, else 0. */
	private static int unsignedBits(JavaBoundary.Type wit) {
		return switch (wit.wit()) {
			case "u8" -> 8;
			case "u16" -> 16;
			case "u32" -> 32;
			case "u64" -> 64;
			default -> 0;
		};
	}

}
