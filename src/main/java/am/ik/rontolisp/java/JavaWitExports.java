package am.ik.rontolisp.java;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import am.ik.rontolisp.LispSymbol;
import am.ik.rontolisp.LispVal;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaClasses.Unit;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The program's {@code @WitExport} classes: a class standing for a WIT world, each
 * function the world exports a static method of it and each interface the world exports a
 * static member class of it, whose static methods are the interface's functions
 * ({@code .kb/java-frontend.md}, "WIT exports"). Each is checked against its world on
 * every target, export by export and type by type, against the one mapping of a WIT type
 * onto a Java type ({@link JavaWitTypes}, {@code @WitImport}'s): the named types of the
 * world are member types of the class, an exported interface's of its member class. The
 * WIT is read through the host boundary ({@link JavaBoundary}), so an export is the one
 * the {@code rontolisp:wit-export} the lowering emits checks.
 *
 * <p>
 * A program whose entry class has no {@code main} starts at the {@code @WitExport}
 * classes of its source tree instead ({@link #entries}): its worlds' exports are its
 * entry points. On a component target the lowering emits a {@code rontolisp:wit-export}
 * of each world, its {@code :names} table naming the function of each export, and
 * {@code :reactor t}: the component lifts no {@code run}, and the first export call runs
 * the program's static initialization. The function is the method where every type is one
 * the flat boundary carries, a defun around it where an unsigned integer crosses, and
 * otherwise a static method of generated Java source ({@link #wrappers}) converting the
 * settled values the canonical ABI lifts into the method's Java values and its answer
 * back -- a {@code result} at the top the envelope, which the directive says its
 * functions answer ({@code :envelope t}), whether the method answers a {@code WitResult}
 * or its ok arm with a {@code WitException} thrown. Everywhere else such a program is
 * refused. A program with a {@code main} is a command: its {@code @WitExport} classes are
 * checked, and exported nowhere.
 */
final class JavaWitExports {

	/** The annotation, in the library a program names it from. */
	static final String WIT_EXPORT = "am.ik.rontolisp.wit.WitExport";

	private final JavaClasses classes;

	/** Each class asked about: its world, or none for one no {@code @WitExport} marks. */
	private final Map<JavaClass, Optional<World>> worlds = new IdentityHashMap<>();

	/**
	 * The method of generated Java source converting around each method an export of a
	 * type outside the flat set is, made once ({@link #wrappers}).
	 */
	private @Nullable Map<JavaClass.Method, JavaClass.Method> wrappers;

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

		/**
		 * The types of each class holding exports, checked against the mapping: the
		 * world's against the member types of the class, an interface's against those of
		 * its member class.
		 */
		private final Map<JavaClass, JavaWitTypes.Table> tables = new IdentityHashMap<>();

		Checker(JavaClass declaration, JavaBoundary.WitWorld world, Source source) {
			this.declaration = declaration;
			this.world = world;
			this.source = source;
		}

		World check() {
			List<Bound> bound = new ArrayList<>();
			for (JavaBoundary.Export export : this.world.exports()) {
				if (export.async()) {
					throw at(this.declaration, "@WitExport: " + shown(export) + " (" + export.at()
							+ ") is an async func, which the Java subset does not implement yet");
				}
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
			JavaWitTypes.Table table = this.tables.computeIfAbsent(holder,
					key -> new Types(holder, scope(export), this.source));
			List<JavaClass.Method> statics = named.stream().filter(method -> method.isStatic).toList();
			if (statics.isEmpty()) {
				if (!named.isEmpty()) {
					throw table.at(named.get(0),
							what + " is a static method in Java, which the host calls with no instance");
				}
				throw at(holder, holder.simpleName() + " declares no method " + name + " for " + shown(export) + " ("
						+ export.at() + "): static " + signature(holder, name, export));
			}
			// the overload of the export's types, else the one a reader means: of the
			// arity, else the first
			JavaClass.Method meant = statics.stream()
				.filter(method -> fits(table, method, export))
				.findFirst()
				.orElse(statics.stream()
					.filter(method -> method.parameters.size() == export.params().size())
					.findFirst()
					.orElse(statics.get(0)));
			List<JavaBoundary.Param> params = export.params();
			if (meant.parameters.size() != params.size()) {
				throw table.at(meant, what + " takes " + params.size() + " argument" + (params.size() == 1 ? "" : "s")
						+ ", not " + meant.parameters.size());
			}
			for (int i = 0; i < params.size(); i++) {
				JavaBoundary.Param param = params.get(i);
				table.expect(param.type(), meant.parameters.get(i), false, meant,
						what + ": its parameter " + param.name());
			}
			table.expectAnswer(export.result(), meant, what);
			return meant;
		}

		/**
		 * Whether a method's declared types are an export's, the types they name left
		 * unchecked: what tells one overload from another.
		 */
		private boolean fits(JavaWitTypes.Table table, JavaClass.Method method, JavaBoundary.Export export) {
			if (method.parameters.size() != export.params().size()) {
				return false;
			}
			try {
				for (int i = 0; i < method.parameters.size(); i++) {
					if (!table.matches(export.params().get(i).type(), method.parameters.get(i), false, method, "")) {
						return false;
					}
				}
				JavaBoundary.Type result = export.result();
				if (result != null && JavaWitImports.thrown(result, method.returnType)) {
					result = result.element();
				}
				return result == null ? method.returnType == Primitive.VOID
						: table.matches(result, method.returnType, false, method, "");
			}
			catch (LispReadException ex) {
				// a type naming a member type the class does not declare
				return false;
			}
		}

		private LispReadException at(JavaClass named, String message) {
			JavaSyntax.ClassDeclaration written = named.declaration;
			return JavaClasses.error(named.source == null ? this.source : named.source,
					written == null ? 0 : written.pos(), message);
		}

		/** An export as a message names it: its label, and its interface or world. */
		private String shown(JavaBoundary.Export export) {
			return export.name() + " of " + scope(export);
		}

		/** The WIT scope an export's types are of, as a message names it. */
		private String scope(JavaBoundary.Export export) {
			return export.iface() == null ? "world " + this.world.name() : export.iface();
		}

	}

	/**
	 * The types of one class holding exports: no export carries a resource's handle
	 * ({@code rontolisp:wit-export} refuses one at its WIT line).
	 */
	private static final class Types extends JavaWitTypes.Table {

		Types(JavaClass holder, String scope, Source source) {
			super(holder, scope, "the class " + holder.simpleName(), source);
		}

		@Override
		void resource(String name, JavaClass named) {
			throw new IllegalStateException("the resource " + name + " crosses no export");
		}

	}

	/** The declaration an export's method has, as a message writes it. */
	private static String signature(JavaClass holder, String name, JavaBoundary.Export export) {
		List<String> params = new ArrayList<>();
		for (JavaBoundary.Param param : export.params()) {
			params.add(JavaWitTypes.javaText(holder, param.type(), false, null, true) + " "
					+ JavaWitImports.lowerCamel(param.name()));
		}
		JavaBoundary.Type result = export.result();
		return (result == null ? "void" : JavaWitTypes.javaText(holder, result, false, null, true)) + " " + name + "("
				+ String.join(", ", params) + ")";
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
	 * each world's {@code rontolisp:wit-export}, the conversion of each flat export an
	 * unsigned integer crosses -- the boundary's value is the number, the Java value its
	 * bits in the signed type of its width -- and the directive naming each export's
	 * function, the program a reactor. A {@code list<u8>} is a {@code byte[]}
	 * ({@code :octets t}), and the function of an export answering a {@code result}
	 * answers its envelope ({@code :envelope t}): each is a wrapper's
	 * ({@link #wrappers}).
	 */
	List<LispVal> directives() {
		Map<JavaClass.Method, JavaClass.Method> wrapped = wrappers();
		List<LispVal> forms = new ArrayList<>();
		for (JavaClass declaration : annotated()) {
			World world = Objects.requireNonNull(of(declaration));
			List<LispVal> table = new ArrayList<>();
			for (Bound bound : world.exports()) {
				JavaClass.Method wrapper = wrapped.get(bound.method());
				LispSymbol function = JavaNames.function(wrapper == null ? bound.method() : wrapper);
				LispSymbol converted = wrapper == null ? conversion(bound, function, forms) : null;
				table.add(JavaForms.list(JavaForms.string(bound.export().key()),
						JavaForms.string((converted == null ? function : converted).name())));
			}
			forms.add(JavaForms.list(new LispSymbol("RONTOLISP:WIT-EXPORT"), JavaForms.string(world.path()),
					new LispSymbol(":WORLD"), JavaForms.string(world.name()), new LispSymbol(":NAMES"),
					JavaForms.list(table), new LispSymbol(":REACTOR"), JavaForms.T, new LispSymbol(":OCTETS"),
					JavaForms.T, new LispSymbol(":ENVELOPE"), JavaForms.T));
		}
		return forms;
	}

	/**
	 * The wrapper of each method that is an export of a type outside the flat set, by the
	 * method: a static method of a class of generated Java source, one class for each
	 * class holding such exports, declared when first asked for. The lowering reaches
	 * each ({@code JavaLowering.exportEntries}), and the directive names its function.
	 */
	Map<JavaClass.Method, JavaClass.Method> wrappers() {
		Map<JavaClass.Method, JavaClass.Method> made = this.wrappers;
		if (made != null) {
			return made;
		}
		// in world order: the order the lowering reaches them in
		made = new LinkedHashMap<>();
		for (JavaClass declaration : annotated()) {
			World world = Objects.requireNonNull(of(declaration));
			Map<JavaClass, List<Bound>> held = new LinkedHashMap<>();
			for (Bound bound : world.exports()) {
				if (bound.export().canonical()) {
					held.computeIfAbsent(bound.method().owner, holder -> new ArrayList<>()).add(bound);
				}
			}
			for (Map.Entry<JavaClass, List<Bound>> holding : held.entrySet()) {
				JavaClass holder = holding.getKey();
				Unit unit = this.classes.unit(holder);
				String simple = holder.binaryName.substring(holder.binaryName.lastIndexOf('.') + 1) + "$WitExport";
				String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
				String iface = holding.getValue().get(0).export().iface();
				Writer writer = new Writer(holder, iface == null ? "world " + world.name() : iface, name);
				this.classes.declareGenerated(name, simple,
						writer.write(simple, unit.packageName(), holding.getValue()));
				JavaClass generated = Objects.requireNonNull(this.classes.qualified(name));
				for (Bound bound : holding.getValue()) {
					for (JavaClass.Method method : generated.methods) {
						if (method.name.equals(writer.names.get(bound.method()))) {
							made.put(bound.method(), method);
						}
					}
				}
			}
		}
		this.wrappers = made;
		return made;
	}

	/**
	 * Writes the Java source of the wrappers of one class's exports: each takes the
	 * values the canonical ABI lifts, calls the method with the Java values they are, and
	 * answers the value the boundary lowers.
	 */
	private static final class Writer extends JavaWitTypes.Converters {

		/** The name of each method's wrapper. */
		final Map<JavaClass.Method, String> names = new IdentityHashMap<>();

		Writer(JavaClass holder, String scope, String binding) {
			// a component's export answers an unsigned integer as its value
			super(holder, scope, binding, true);
		}

		@Override
		String handleClass(String resource) {
			throw new IllegalStateException("the resource " + resource + " crosses no export");
		}

		@Override
		String asyncClass(JavaBoundary.Type wit, @Nullable JavaType declared) {
			throw new IllegalStateException(wit.wit() + " crosses no export");
		}

		String write(String simple, @Nullable String packageName, List<Bound> bounds) {
			StringBuilder methods = new StringBuilder();
			for (Bound bound : bounds) {
				method(bound, methods);
			}
			StringBuilder text = new StringBuilder();
			if (packageName != null) {
				text.append("package ").append(packageName).append(";\n");
			}
			return text.append("public final class ")
				.append(simple)
				.append(" {\n")
				.append(methods)
				.append(this.converters)
				.append("}\n")
				.toString();
		}

		private void method(Bound bound, StringBuilder out) {
			JavaClass.Method method = bound.method();
			JavaBoundary.Export export = bound.export();
			// named as the method, but where that is a converter's name
			String name = method.name.matches("(in|out)\\d+") ? method.name + "_" : method.name;
			this.names.put(method, name);
			List<String> parameters = new ArrayList<>();
			List<String> arguments = new ArrayList<>();
			for (int i = 0; i < export.params().size(); i++) {
				parameters.add("Object a" + i);
				arguments.add(in(export.params().get(i).type(), method.parameters.get(i), "a" + i));
			}
			String call = this.owner.name + "." + method.name + "(" + String.join(", ", arguments) + ")";
			JavaBoundary.Type result = export.result();
			out.append("    static ")
				.append(result == null ? "void" : "Object")
				.append(' ')
				.append(name)
				.append('(')
				.append(String.join(", ", parameters))
				.append(") {\n");
			String v = JavaWitTypes.VALUES;
			if (result == null) {
				out.append("        ").append(call).append(";\n");
			}
			else if (JavaWitImports.thrown(result, method.returnType)) {
				// the method answers the ok arm and throws the error arm: the envelope
				// of either
				JavaBoundary.Type ok = result.element();
				JavaBoundary.Type error = result.error();
				out.append("        try {\n            ");
				if (ok == null) {
					out.append(call)
						.append(";\n            return ")
						.append(v)
						.append(".cons(")
						.append(v)
						.append(".label(\"ok\"), null);\n");
				}
				else {
					out.append("return ")
						.append(v)
						.append(".cons(")
						.append(v)
						.append(".label(\"ok\"), ")
						.append(out(ok, method.returnType, call))
						.append(");\n");
				}
				out.append("        }\n        catch (")
					.append(JavaWitImports.WIT_EXCEPTION)
					.append(" e) {\n            return ")
					.append(v)
					.append(".cons(")
					.append(v)
					.append(".label(\"error\"), ")
					.append(error == null ? "null"
							: out(error, null,
									"((" + JavaWitTypes.javaText(this.owner, error, true, null, false)
											+ ") e.error())"))
					.append(");\n        }\n");
			}
			else {
				out.append("        return ").append(out(result, method.returnType, call)).append(";\n");
			}
			out.append("    }\n");
		}

	}

	/**
	 * The function converting a flat export's unsigned integers around its method, added
	 * to the forms, or {@code null} where nothing needs converting.
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

	/**
	 * The width of an unsigned integer narrower than a {@code long}, else 0. A
	 * {@code u64} is the {@code long} as it is: up to {@code Long.MAX_VALUE} the two are
	 * one number, and past it the flat boundary has no value -- it traps on a negative
	 * answer and on such an argument, where a mask would hand it a number the wasm-GC
	 * integer rounds and the scalar backend cannot write.
	 */
	private static int unsignedBits(JavaBoundary.Type wit) {
		return switch (String.valueOf(wit.definition())) {
			case "u8" -> 8;
			case "u16" -> 16;
			case "u32" -> 32;
			default -> 0;
		};
	}

}
