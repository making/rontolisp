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
 * refused. An {@code async func} is a method answering its value, as any other: the
 * directive lifts it async, so its call's task may wait where the method blocks. A
 * program with a {@code main} is a command: its {@code @WitExport} classes are checked,
 * and exported nowhere.
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
	 * @param drops the destructor of each resource whose class declares {@code close()}
	 * @param imported the member interface of a {@code @WitImport} interface standing for
	 * each resource the component imports that an export names, by
	 * {@code <interface id>#<resource>}
	 * @param described the world as the boundary describes it
	 */
	record World(JavaClass declaration, String name, String path, List<Bound> exports, List<Drop> drops,
			Map<String, JavaClass> imported, JavaBoundary.WitWorld described) {
	}

	/**
	 * A method and the export it is: a static method of the world's class or of an
	 * interface's member class; a resource's constructor, instance method or static
	 * method, of the class standing for the resource.
	 *
	 * @param export the export
	 * @param method the method
	 * @param holder the class whose member types the export's named types are: the
	 * world's class, or the interface's member class
	 */
	record Bound(JavaBoundary.Export export, JavaClass.Method method, JavaClass holder) {
	}

	/**
	 * A resource's destructor: {@code close()} of the class standing for it, which the
	 * component calls when a holder releases a handle.
	 *
	 * @param resource the resource
	 * @param method its class's {@code close()}
	 * @param holder the interface's member class
	 */
	record Drop(JavaBoundary.Resource resource, JavaClass.Method method, JavaClass holder) {
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
		private final Map<JavaClass, Types> tables = new IdentityHashMap<>();

		/** The member interface standing for each imported resource met. */
		private final Map<String, JavaClass> imported = new LinkedHashMap<>();

		Checker(JavaClass declaration, JavaBoundary.WitWorld world, Source source) {
			this.declaration = declaration;
			this.world = world;
			this.source = source;
		}

		World check() {
			List<Bound> bound = new ArrayList<>();
			for (JavaBoundary.Export export : this.world.exports()) {
				JavaClass holder = holder(export.iface(), export.at());
				bound.add(new Bound(export, method(holder, export), holder));
			}
			// a resource's class may declare close(), its destructor; a resource no
			// export
			// names needs no class
			List<Drop> drops = new ArrayList<>();
			for (JavaBoundary.Resource resource : this.world.resources()) {
				JavaClass holder = holder(resource.iface(), resource.at());
				if (holder.members.get(JavaWitImports.upperCamel(resource.name())) == null) {
					continue;
				}
				JavaClass.Method close = close(resourceClass(holder, resource.name(), resource.iface(), resource.at()),
						resource);
				if (close != null) {
					drops.add(new Drop(resource, close, holder));
				}
			}
			return new World(this.declaration, this.world.name(), this.world.path(), List.copyOf(bound),
					List.copyOf(drops), java.util.Collections.unmodifiableMap(this.imported), this.world);
		}

		/** The class an export's method is of: the class, or an interface's member. */
		private JavaClass holder(@Nullable String iface, String at) {
			if (iface == null) {
				return this.declaration;
			}
			String name = JavaWitImports.upperCamel(interfaceName(iface));
			JavaClass member = this.declaration.members.get(name);
			if (member == null) {
				throw at(this.declaration,
						this.declaration.simpleName() + " declares no member class " + name
								+ ", which is the interface " + iface + " the world " + this.world.name() + " exports ("
								+ at + "): a static member class whose static methods are its functions");
			}
			return member;
		}

		/**
		 * The class standing for a resource the world exports: the static member class of
		 * the interface's class named after it.
		 */
		private JavaClass resourceClass(JavaClass holder, String resource, String iface, String at) {
			String name = JavaWitImports.upperCamel(resource);
			JavaClass named = holder.members.get(name);
			if (named == null) {
				throw at(holder,
						holder.simpleName() + " declares no member class " + name + ", which is the resource "
								+ resource + " of " + iface + " the world " + this.world.name() + " exports (" + at
								+ "): a static member class whose constructor, methods and static methods are its "
								+ "functions");
			}
			String what = holder.simpleName() + "." + name + " (" + resource + " of " + iface + ", " + at + ")";
			if (named.isInterface || named.isEnum || named.isAnnotation) {
				throw at(named, what + " is a resource: a class in Java, its constructor, methods and static methods "
						+ "the resource's");
			}
			if (named.hasOuterInstance()) {
				throw at(named, what + " is a static member class in Java, which the host makes with no instance of "
						+ holder.simpleName());
			}
			return named;
		}

		/**
		 * A resource class's {@code close()}, its destructor, or {@code null} for none:
		 * an instance method of no parameter, declared or inherited.
		 */
		private JavaClass.@Nullable Method close(JavaClass named, JavaBoundary.Resource resource) {
			for (JavaClass type = named; type != null; type = type.superclass) {
				for (JavaClass.Method method : type.methods) {
					if (!method.name.equals("close") || !method.parameters.isEmpty() || method.isAbstract) {
						continue;
					}
					if (method.isStatic) {
						throw at(named,
								JavaWitTypes.simpleText(named) + "'s close() is the destructor of the resource "
										+ resource.name() + " of " + resource.iface() + " (" + resource.at()
										+ "): an instance method in Java, which the component calls on the object a "
										+ "handle stood for");
					}
					return method;
				}
			}
			return null;
		}

		/**
		 * The method implementing an export, checked against it: a static method; a
		 * resource's constructor, instance method or static method.
		 */
		private JavaClass.Method method(JavaClass holder, JavaBoundary.Export export) {
			JavaBoundary.Kind kind = export.kind();
			String resource = export.resource();
			JavaClass owner = resource == null ? holder
					: resourceClass(holder, resource, Objects.requireNonNull(export.iface()), export.at());
			boolean constructor = kind == JavaBoundary.Kind.CONSTRUCTOR;
			boolean instance = kind == JavaBoundary.Kind.METHOD;
			String name = constructor ? "<init>" : JavaWitImports.lowerCamel(export.function());
			String what = (constructor ? owner.simpleName() : name) + " (" + shown(export) + ", " + export.at() + ")";
			// a method's handle is its receiver; a constructor answers the object it
			// makes
			List<JavaBoundary.Param> params = instance ? export.params().subList(1, export.params().size())
					: export.params();
			JavaBoundary.Type result = constructor ? null : export.result();
			List<JavaClass.Method> named = new ArrayList<>();
			for (JavaClass type = owner; type != null; type = instance ? type.superclass : null) {
				for (JavaClass.Method method : type.methods) {
					if (method.name.equals(name) && !method.isAbstract) {
						named.add(method);
					}
				}
			}
			Types table = this.tables.computeIfAbsent(holder,
					key -> new Types(holder, scope(export), this.source, this.declaration, this.world, this.imported));
			List<JavaClass.Method> candidates = constructor ? named
					: named.stream().filter(method -> method.isStatic != instance).toList();
			if (candidates.isEmpty()) {
				if (!named.isEmpty()) {
					throw table.at(named.get(0),
							what + (instance
									? " is an instance method in Java, which the host calls on the object a handle "
											+ "stands for"
									: " is a static method in Java, which the host calls with no instance"));
				}
				throw at(owner, owner.simpleName() + " declares no " + (constructor ? "constructor" : "method " + name)
						+ " for " + shown(export) + " (" + export.at() + "): " + signature(holder, owner, export));
			}
			if (constructor && owner.isAbstract) {
				throw at(owner, what + ": " + owner.simpleName() + " is abstract, which no constructor makes");
			}
			// the overload of the export's types, else the one a reader means: of the
			// arity, else the first
			JavaClass.Method meant = candidates.stream()
				.filter(method -> fits(table, method, params, result, constructor))
				.findFirst()
				.orElse(candidates.stream()
					.filter(method -> method.parameters.size() == params.size())
					.findFirst()
					.orElse(candidates.get(0)));
			if (meant.parameters.size() != params.size()) {
				throw table.at(meant, what + " takes " + params.size() + " argument" + (params.size() == 1 ? "" : "s")
						+ ", not " + meant.parameters.size());
			}
			for (int i = 0; i < params.size(); i++) {
				JavaBoundary.Param param = params.get(i);
				table.expect(param.type(), meant.parameters.get(i), false, meant,
						what + ": its parameter " + param.name());
			}
			if (!constructor) {
				table.expectAnswer(result, meant, what);
			}
			return meant;
		}

		/**
		 * Whether a method's declared types are an export's, the types they name left
		 * unchecked: what tells one overload from another.
		 */
		private boolean fits(JavaWitTypes.Table table, JavaClass.Method method, List<JavaBoundary.Param> params,
				JavaBoundary.@Nullable Type answer, boolean constructor) {
			if (method.parameters.size() != params.size()) {
				return false;
			}
			try {
				for (int i = 0; i < method.parameters.size(); i++) {
					if (!table.matches(params.get(i).type(), method.parameters.get(i), false, method, "")) {
						return false;
					}
				}
				if (constructor) {
					return true;
				}
				JavaBoundary.Type result = answer;
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
	 * The types of one class holding exports. A handle is of a resource the world exports
	 * -- the class standing for it, a member of its interface's class --, or of one the
	 * component imports: the member interface of the {@code @WitImport} interface
	 * standing for the interface defining it, told by the type written.
	 */
	private final class Types extends JavaWitTypes.Table {

		private final JavaClass declaration;

		private final JavaBoundary.WitWorld world;

		/** The member interface standing for each imported resource met. */
		private final Map<String, JavaClass> imported;

		Types(JavaClass holder, String scope, Source source, JavaClass declaration, JavaBoundary.WitWorld world,
				Map<String, JavaClass> imported) {
			super(holder, scope, "the class " + holder.simpleName(), source);
			this.declaration = declaration;
			this.world = world;
			this.imported = imported;
		}

		@Override
		boolean matches(JavaBoundary.Type wit, JavaType declared, boolean boxed, JavaClass.Method method, String what) {
			if (wit.rep() != JavaBoundary.Rep.HANDLE || exportedResource(this.world, wit)) {
				return super.matches(wit, declared, boxed, method, what);
			}
			String name = Objects.requireNonNull(wit.definition());
			String home = String.valueOf(wit.home());
			JavaClass named = declared instanceof JavaType.ClassType type && type.arguments().isEmpty()
					? type.declaration() : null;
			JavaClass outer = named == null ? null : named.outer;
			JavaWitImports.Import imported = outer == null ? null : JavaWitExports.this.classes.wit.of(outer);
			if (named == null || outer == null || imported == null || !imported.id().equals(home)
					|| outer.members.get(JavaWitImports.upperCamel(name)) != named
					|| !imported.resources().containsKey(name)) {
				throw at(method,
						what + " is " + wit.wit() + ", the resource " + name + " of " + home
								+ ", which the component imports: in Java the member interface "
								+ JavaWitImports.upperCamel(name) + " of the @WitImport interface standing for " + home
								+ ", not " + declared);
			}
			JavaClass known = this.imported.putIfAbsent(home + "#" + name, named);
			if (known != null && known != named) {
				throw at(method, what + " is " + wit.wit() + ", the resource " + name + " of " + home + ", which is "
						+ known.name + " elsewhere in " + this.declaration.simpleName() + ", not " + declared);
			}
			return true;
		}

		@Override
		@Nullable JavaClass used(JavaBoundary.Type wit, @Nullable JavaType declared, JavaClass.Method method, String what) {
			if (wit.rep() != JavaBoundary.Rep.HANDLE) {
				return null;
			}
			JavaClass known = classOf(wit);
			if (known != null) {
				return known;
			}
			String name = Objects.requireNonNull(wit.definition());
			if (!exportedResource(this.world, wit)) {
				// no type is written where it stands (an error arm thrown)
				throw at(method, what + " is " + wit.wit() + ", a resource of " + wit.home() + ", which a type is "
						+ "written for where it is handed over: the method answers a WitResult of it");
			}
			String holder = JavaWitImports.upperCamel(interfaceName(String.valueOf(wit.home())));
			throw at(method, what + " is " + wit.wit() + ", the resource " + name + " of " + wit.home() + ", which is "
					+ holder + "." + JavaWitImports.upperCamel(name) + " in Java, a static member class of " + holder
					+ " the class " + this.declaration.simpleName() + " declares; it declares none " + "of that name");
		}

		@Override
		@Nullable JavaClass classOf(JavaBoundary.Type wit) {
			return handleClassIn(this.declaration, this.world, this.imported, wit);
		}

		@Override
		void resource(String name, JavaClass named) {
			// a class the world exports is checked by its functions, a member interface
			// by
			// its @WitImport interface's check
		}

	}

	/**
	 * Whether a handle is of a resource the world exports, which the component defines.
	 */
	private static boolean exportedResource(JavaBoundary.WitWorld world, JavaBoundary.Type wit) {
		for (JavaBoundary.Resource resource : world.resources()) {
			if (resource.name().equals(wit.definition()) && resource.iface().equals(wit.home())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * The class a handle is, or {@code null} where none stands for it: the class of a
	 * resource the world exports, the member interface met for one the component imports.
	 */
	private static @Nullable JavaClass handleClassIn(JavaClass declaration, JavaBoundary.WitWorld world,
			Map<String, JavaClass> imported, JavaBoundary.Type wit) {
		String home = wit.home();
		String name = wit.definition();
		if (wit.rep() != JavaBoundary.Rep.HANDLE || home == null || name == null) {
			return null;
		}
		if (!exportedResource(world, wit)) {
			return imported.get(home + "#" + name);
		}
		JavaClass holder = declaration.members.get(JavaWitImports.upperCamel(interfaceName(home)));
		JavaClass named = holder == null ? null : holder.members.get(JavaWitImports.upperCamel(name));
		return named == null || named.isInterface || named.isEnum || named.isAnnotation ? null : named;
	}

	/** The declaration an export's method has, as a message writes it. */
	private static String signature(JavaClass holder, JavaClass owner, JavaBoundary.Export export) {
		JavaBoundary.Kind kind = export.kind();
		List<JavaBoundary.Param> params = kind == JavaBoundary.Kind.METHOD
				? export.params().subList(1, export.params().size()) : export.params();
		List<String> written = new ArrayList<>();
		for (JavaBoundary.Param param : params) {
			written.add(JavaWitTypes.javaText(holder, param.type(), false, null, true) + " "
					+ JavaWitImports.lowerCamel(param.name()));
		}
		String parameters = "(" + String.join(", ", written) + ")";
		if (kind == JavaBoundary.Kind.CONSTRUCTOR) {
			return "public " + owner.simpleName() + parameters;
		}
		JavaBoundary.Type result = export.result();
		return (kind == JavaBoundary.Kind.METHOD ? "" : "static ")
				+ (result == null ? "void" : JavaWitTypes.javaText(holder, result, false, null, true)) + " "
				+ JavaWitImports.lowerCamel(export.function()) + parameters;
	}

	/**
	 * The plain name of an exported interface: {@code add} of
	 * {@code docs:adder/add@0.1.0}, an inline one's own.
	 */
	static String interfaceName(String iface) {
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
	 * function and each resource's destructor's, the program a reactor. A
	 * {@code list<u8>} is a {@code byte[]} ({@code :octets t}), and the function of an
	 * export answering a {@code result} answers its envelope ({@code :envelope t}): each
	 * is a wrapper's ({@link #wrappers}).
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
			for (Drop drop : world.drops()) {
				table.add(JavaForms.list(JavaForms.string(drop.resource().key()), JavaForms
					.string(JavaNames.function(Objects.requireNonNull(wrapped.get(drop.method()))).name())));
			}
			forms.add(JavaForms.list(new LispSymbol("RONTOLISP:WIT-EXPORT"), JavaForms.string(world.path()),
					new LispSymbol(":WORLD"), JavaForms.string(world.name()), new LispSymbol(":NAMES"),
					JavaForms.list(table), new LispSymbol(":REACTOR"), JavaForms.T, new LispSymbol(":OCTETS"),
					JavaForms.T, new LispSymbol(":ENVELOPE"), JavaForms.T));
		}
		return forms;
	}

	/**
	 * The wrapper of each method that is an export of a type outside the flat set -- a
	 * resource's constructor and methods among them -- and of each resource's
	 * {@code close()}, by the method: a static method of a class of generated Java
	 * source, one class for each class holding such exports, declared when first asked
	 * for. The lowering reaches each ({@code JavaLowering.exportEntries}), and the
	 * directive names its function.
	 */
	Map<JavaClass.Method, JavaClass.Method> wrappers() {
		Map<JavaClass.Method, JavaClass.Method> made = this.wrappers;
		if (made != null) {
			return made;
		}
		// in world order: the order the lowering reaches them in
		made = new LinkedHashMap<>();
		// a handle of an imported resource lent to an export is an object of its
		// binding that the call closes, which the binding is written knowing
		for (JavaClass declaration : annotated()) {
			World world = Objects.requireNonNull(of(declaration));
			for (Bound bound : world.exports()) {
				for (JavaBoundary.Param param : bound.export().params()) {
					lendings(param.type(), world, new java.util.HashSet<>());
				}
			}
		}
		for (JavaClass declaration : annotated()) {
			World world = Objects.requireNonNull(of(declaration));
			Map<JavaClass, List<Bound>> held = new LinkedHashMap<>();
			Map<JavaClass, List<Drop>> dropped = new LinkedHashMap<>();
			for (Bound bound : world.exports()) {
				if (bound.export().canonical()) {
					held.computeIfAbsent(bound.holder(), holder -> new ArrayList<>()).add(bound);
				}
			}
			for (Drop drop : world.drops()) {
				held.computeIfAbsent(drop.holder(), holder -> new ArrayList<>());
				dropped.computeIfAbsent(drop.holder(), holder -> new ArrayList<>()).add(drop);
			}
			for (Map.Entry<JavaClass, List<Bound>> holding : held.entrySet()) {
				JavaClass holder = holding.getKey();
				Unit unit = this.classes.unit(holder);
				String simple = holder.binaryName.substring(holder.binaryName.lastIndexOf('.') + 1) + "$WitExport";
				String name = unit.packageName() == null ? simple : unit.packageName() + "." + simple;
				List<Drop> drops = dropped.getOrDefault(holder, List.of());
				String iface = holding.getValue().isEmpty() ? drops.get(0).resource().iface()
						: holding.getValue().get(0).export().iface();
				Writer writer = new Writer(holder, iface == null ? "world " + world.name() : iface, name, world);
				this.classes.declareGenerated(name, simple,
						writer.write(simple, unit.packageName(), holding.getValue(), drops));
				JavaClass generated = Objects.requireNonNull(this.classes.qualified(name));
				for (Map.Entry<JavaClass.Method, String> wrapper : writer.names.entrySet()) {
					for (JavaClass.Method method : generated.methods) {
						if (method.name.equals(wrapper.getValue())) {
							made.put(wrapper.getKey(), method);
						}
					}
				}
			}
		}
		this.wrappers = made;
		return made;
	}

	/**
	 * Tells the bindings of each imported resource a type lends an export's call
	 * ({@link JavaWitImports#lend}).
	 */
	private void lendings(JavaBoundary.Type wit, World world, java.util.Set<JavaClass> told) {
		if (wit.rep() == JavaBoundary.Rep.HANDLE && wit.borrow() && !exportedResource(world.described(), wit)) {
			JavaClass named = world.imported().get(wit.home() + "#" + wit.definition());
			if (named != null && told.add(named)) {
				this.classes.wit.lend(Objects.requireNonNull(named.outer), Objects.requireNonNull(wit.definition()));
			}
		}
		if (wit.element() != null) {
			lendings(wit.element(), world, told);
		}
		if (wit.error() != null) {
			lendings(wit.error(), world, told);
		}
		for (JavaBoundary.Part part : wit.parts()) {
			if (part.type() != null) {
				lendings(part.type(), world, told);
			}
		}
	}

	/** Whether a type holds a handle of an imported resource lent for the call alone. */
	private static boolean lent(JavaBoundary.Type wit, JavaBoundary.WitWorld world) {
		if (wit.rep() == JavaBoundary.Rep.HANDLE) {
			return wit.borrow() && !exportedResource(world, wit);
		}
		boolean lends = wit.element() != null && lent(wit.element(), world)
				|| wit.error() != null && lent(wit.error(), world);
		for (JavaBoundary.Part part : wit.parts()) {
			lends |= part.type() != null && lent(part.type(), world);
		}
		return lends;
	}

	/**
	 * Writes the Java source of the wrappers of one class's exports: each takes the
	 * values the canonical ABI lifts, calls the method -- a resource's constructor makes
	 * the object, a method is called on the object its handle stands for -- with the Java
	 * values they are, and answers the value the boundary lowers. A resource's destructor
	 * calls {@code close()} on the object. A handle of a resource the world exports is
	 * the object itself, which the component keeps behind it; one of a resource the
	 * component imports is an object of its {@code @WitImport} interface's binding over
	 * the integer handle.
	 */
	private final class Writer extends JavaWitTypes.Converters {

		/** The name of each method's wrapper. */
		final Map<JavaClass.Method, String> names = new LinkedHashMap<>();

		private final World world;

		Writer(JavaClass holder, String scope, String binding, World world) {
			// a component's export answers an unsigned integer as its value
			super(holder, scope, binding, true);
			this.world = world;
		}

		@Override
		String handleClass(String resource) {
			throw new IllegalStateException("the resource " + resource + " is a class of the program");
		}

		@Override
		String asyncClass(JavaBoundary.Type wit, @Nullable JavaType declared) {
			throw new IllegalStateException(wit.wit() + " crosses no export");
		}

		@Override
		@Nullable JavaClass classOf(JavaBoundary.Type wit) {
			return handleClassIn(this.world.declaration(), this.world.described(), this.world.imported(), wit);
		}

		@Override
		boolean lends(JavaBoundary.Type wit) {
			return lent(wit, this.world.described());
		}

		@Override
		boolean handle(boolean in, JavaBoundary.Type wit, String javaType, StringBuilder body) {
			JavaClass named = Objects.requireNonNull(classOf(wit));
			String v = JavaWitTypes.VALUES;
			if (named.isInterface) {
				// a resource the component imports: the binding's object over the
				// integer,
				// one lent for the call holding the call's flag
				String handle = JavaWitExports.this.classes.wit.handleClass(Objects.requireNonNull(named.outer),
						Objects.requireNonNull(wit.definition()));
				if (in && wit.borrow()) {
					body.append("        ")
						.append(handle)
						.append(" h = new ")
						.append(handle)
						.append('(')
						.append(v)
						.append(".inHandle(v));\n        h.lent = lent;\n        return h;\n");
					return true;
				}
				if (in) {
					body.append("        return new ").append(handle).append('(').append(v).append(".inHandle(v));\n");
					return true;
				}
				body.append("        if (!(v instanceof ")
					.append(handle)
					.append(")) {\n            throw new IllegalArgumentException(\"a ")
					.append(wit.definition())
					.append(" of ")
					.append(wit.home())
					.append(" the host did not make\");\n        }\n        ")
					.append(handle)
					.append(" h = (")
					.append(handle)
					.append(") v;\n        int held = h.live();\n");
				if (!wit.borrow()) {
					// an own handle passes to the caller
					body.append("        h.handle = -1;\n");
				}
				body.append("        return ").append(v).append(".outHandle(held);\n");
				return true;
			}
			// a resource the world exports: the object, which the component keeps
			if (in) {
				body.append("        return (").append(javaType).append(") v;\n");
			}
			else {
				body.append("        if (v == null) {\n            throw new NullPointerException();\n        }\n")
					.append("        return v;\n");
			}
			return true;
		}

		String write(String simple, @Nullable String packageName, List<Bound> bounds, List<Drop> drops) {
			StringBuilder methods = new StringBuilder();
			for (Bound bound : bounds) {
				method(bound, methods);
			}
			for (Drop drop : drops) {
				String name = wrapperName(drop.resource().name() + "-drop");
				this.names.put(drop.method(), name);
				methods.append("    static void ")
					.append(name)
					.append("(Object a0) {\n        ((")
					.append(drop.method().owner.name)
					.append(") a0).close();\n    }\n");
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

		/**
		 * A wrapper's name: a member's in lower camel case, but where it is a
		 * converter's.
		 */
		private String wrapperName(String member) {
			String name = JavaWitImports.lowerCamel(member);
			return name.matches("(in|out)\\d+") ? name + "_" : name;
		}

		private void method(Bound bound, StringBuilder out) {
			JavaClass.Method method = bound.method();
			JavaBoundary.Export export = bound.export();
			JavaBoundary.Kind kind = export.kind();
			// named as the method, a resource's function as its member
			String name = kind == JavaBoundary.Kind.FUNCTION ? wrapperName(method.name) : wrapperName(export.member());
			this.names.put(method, name);
			List<String> parameters = new ArrayList<>();
			List<String> arguments = new ArrayList<>();
			// a method is called on the object its handle stands for
			int first = kind == JavaBoundary.Kind.METHOD ? 1 : 0;
			for (int i = 0; i < export.params().size(); i++) {
				parameters.add("Object a" + i);
				if (i >= first) {
					arguments.add(in(export.params().get(i).type(), method.parameters.get(i - first), "a" + i));
				}
			}
			String called = "(" + String.join(", ", arguments) + ")";
			String call = switch (kind) {
				case CONSTRUCTOR -> "new " + method.owner.name + called;
				case METHOD -> in(export.params().get(0).type(), new JavaType.ClassType(method.owner, List.of()), "a0")
						+ "." + method.name + called;
				default -> method.owner.name + "." + method.name + called;
			};
			JavaBoundary.Type result = export.result();
			out.append("    static ")
				.append(result == null ? "void" : "Object")
				.append(' ')
				.append(name)
				.append('(')
				.append(String.join(", ", parameters))
				.append(") {\n");
			// a handle lent for the call is closed when it ends
			boolean lends = false;
			for (JavaBoundary.Param param : export.params()) {
				lends |= lends(param.type());
			}
			if (lends) {
				out.append("        boolean[] lent = { true };\n        try {\n");
				body(bound, call, out);
				out.append("        }\n        finally {\n            lent[0] = false;\n        }\n");
			}
			else {
				body(bound, call, out);
			}
			out.append("    }\n");
		}

		/**
		 * The statements of a wrapper's body, after its parameters: the call and the
		 * answer.
		 */
		private void body(Bound bound, String call, StringBuilder out) {
			JavaClass.Method method = bound.method();
			JavaBoundary.Kind kind = bound.export().kind();
			JavaBoundary.Type result = bound.export().result();
			String v = JavaWitTypes.VALUES;
			if (result == null) {
				out.append("        ").append(call).append(";\n");
			}
			else if (kind == JavaBoundary.Kind.CONSTRUCTOR) {
				// the object made is the resource
				out.append("        return ").append(call).append(";\n");
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
					.append(error == null ? "null" : out(error, null, "((" + text(error, true, null) + ") e.error())"))
					.append(");\n        }\n");
			}
			else {
				out.append("        return ").append(out(result, method.returnType, call)).append(";\n");
			}
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
