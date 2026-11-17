package am.ik.rontolisp.java;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.rontolisp.SourceLocation;
import am.ik.rontolisp.java.JavaClass.Source;
import am.ik.rontolisp.java.JavaSyntax.ClassDeclaration;
import am.ik.rontolisp.java.JavaSyntax.CompilationUnit;
import am.ik.rontolisp.java.JavaSyntax.Import;
import am.ik.rontolisp.java.JavaSyntax.Parameter;
import am.ik.rontolisp.java.JavaSyntax.TypeName;
import am.ik.rontolisp.java.JavaType.ArrayType;
import am.ik.rontolisp.java.JavaType.ClassType;
import am.ik.rontolisp.java.JavaType.Primitive;
import am.ik.rontolisp.reader.LispReadException;
import org.jspecify.annotations.Nullable;

/**
 * The class table of one program: the classes its file declares and the classes of the
 * library it names, found by name where it first names them. A library class is either
 * one whose values are Lisp values ({@link JavaIntrinsics}) or Java source shipped in the
 * jar beside this package ({@code library/java/lang/Throwable.java}), read and lowered
 * like the program's own.
 */
final class JavaClasses {

	/** The packages of the library: what {@code import module java.base} imports. */
	private static final List<String> LIBRARY_PACKAGES = List.of("java.lang", "java.util");

	/**
	 * The file a class was declared in: what a simple name is resolved against.
	 *
	 * @param source its text
	 * @param packageName its package, or {@code null}
	 * @param imports the single-type imports, by simple name
	 * @param onDemand the packages imported on demand
	 * @param declared the classes it declares, by simple name
	 */
	record Unit(Source source, @Nullable String packageName, Map<String, Import> imports, List<String> onDemand,
			Map<String, JavaClass> declared) {
	}

	private final Map<String, JavaClass> classes = new LinkedHashMap<>();

	private final Map<String, JavaClass> lispNames = new HashMap<>();

	private final Map<JavaClass, Unit> units = new IdentityHashMap<>();

	private final Set<String> missing = new HashSet<>();

	private final Library library;

	/**
	 * The classes of the library that are no Java source: made by name, checked by type.
	 */
	interface Library {

		/** Makes the class of a qualified name and adds it, or answers {@code null}. */
		@Nullable JavaClass create(String name, JavaClasses classes);

		/** Refuses the type arguments a class of the library does not take yet. */
		void check(ClassType type, Source source, int pos);

	}

	JavaClasses(Library library) {
		this.library = library;
	}

	static LispReadException error(@Nullable Source source, int pos, String message) {
		return new LispReadException(message,
				source == null ? null : SourceLocation.at(source.file(), pos, source.text()));
	}

	static LispReadException unsupported(@Nullable Source source, int pos, String what) {
		return error(source, pos, what + " is not supported by the experimental Java subset yet");
	}

	Collection<JavaClass> all() {
		return this.classes.values();
	}

	int size() {
		return this.classes.size();
	}

	Unit unit(JavaClass declaration) {
		Unit unit = this.units.get(declaration);
		if (unit == null) {
			throw new IllegalStateException(declaration + " was read from no file");
		}
		return unit;
	}

	/**
	 * Adds a class to the table. Two classes whose Lisp names are one -- a class of the
	 * program named as a {@code java.lang} class the program also uses -- are refused:
	 * every name made of them would be shared.
	 */
	void register(JavaClass declaration) {
		JavaClass namesake = this.lispNames.get(declaration.lispName());
		if (namesake != null && namesake != declaration) {
			JavaClass ours = namesake.name.startsWith("java.") ? declaration : namesake;
			JavaClass theirs = ours == namesake ? declaration : namesake;
			throw unsupported(ours.source, ours.declaration == null ? 0 : ours.declaration.pos(),
					"a class named as " + theirs.name + ", which the program also uses,");
		}
		this.classes.put(declaration.name, declaration);
		this.lispNames.put(declaration.lispName(), declaration);
	}

	/**
	 * Adds the classes a file declares: every class first, so that one may name another,
	 * then what each extends and its members.
	 * @param tree the file
	 * @param source its text
	 * @return the classes, in the file's order
	 */
	List<JavaClass> declare(CompilationUnit tree, Source source) {
		Map<String, Import> imports = new HashMap<>();
		List<String> onDemand = new ArrayList<>();
		boolean compact = tree.classes().stream().anyMatch(ClassDeclaration::implicit);
		if (compact) {
			onDemand.addAll(LIBRARY_PACKAGES);
		}
		for (Import imported : tree.imports()) {
			if (imported.module()) {
				if (!imported.name().equals("java.base")) {
					throw unsupported(source, imported.pos(), "the module " + imported.name());
				}
				onDemand.addAll(LIBRARY_PACKAGES);
			}
			else if (imported.onDemand()) {
				onDemand.add(imported.name());
			}
			else {
				imports.put(imported.name().substring(imported.name().lastIndexOf('.') + 1), imported);
			}
		}
		Unit unit = new Unit(source, tree.packageName(), imports, onDemand, new LinkedHashMap<>());
		List<JavaClass> declared = new ArrayList<>();
		for (ClassDeclaration declaration : tree.classes()) {
			String name = tree.packageName() == null ? declaration.name()
					: tree.packageName() + "." + declaration.name();
			JavaClass made = new JavaClass(name, declaration.isInterface(),
					declaration.isInterface() || declaration.modifiers().contains("abstract"), false, 0, declaration,
					source);
			if (this.classes.containsKey(name) || unit.declared().containsKey(declaration.name())) {
				throw error(source, declaration.pos(), "duplicate class: " + name);
			}
			unit.declared().put(declaration.name(), made);
			this.units.put(made, unit);
			register(made);
			declared.add(made);
		}
		for (JavaClass made : declared) {
			supertypes(made, unit);
		}
		for (JavaClass made : declared) {
			for (JavaClass up = made.superclass; up != null; up = up.superclass) {
				if (up == made) {
					throw error(source, declaration(made).pos(), "cyclic inheritance involving " + made.name);
				}
			}
			members(made, unit);
		}
		return declared;
	}

	private static ClassDeclaration declaration(JavaClass made) {
		return java.util.Objects.requireNonNull(made.declaration);
	}

	private void supertypes(JavaClass made, Unit unit) {
		ClassDeclaration declaration = declaration(made);
		Source source = unit.source();
		TypeName extended = declaration.superclass();
		if (extended != null) {
			JavaClass superclass = require(extended.name(), extended.pos(), unit);
			if (superclass.isInterface) {
				throw error(source, extended.pos(), "no interface expected here");
			}
			if (superclass.intrinsic && !superclass.name.equals("java.lang.Object")) {
				throw unsupported(source, extended.pos(), "a class extending " + superclass.name);
			}
			made.superclass = superclass.intrinsic ? null : superclass;
		}
		for (TypeName implemented : declaration.interfaces()) {
			JavaClass type = require(implemented.name(), implemented.pos(), unit);
			if (!type.isInterface) {
				throw error(source, implemented.pos(), "interface expected here");
			}
			if (type.intrinsic) {
				throw unsupported(source, implemented.pos(), "a class implementing " + type.name);
			}
			made.interfaces.add(type);
		}
	}

	private void members(JavaClass made, Unit unit) {
		ClassDeclaration declaration = declaration(made);
		Source source = unit.source();
		Set<String> fieldNames = new HashSet<>();
		for (JavaSyntax.Field field : declaration.fields()) {
			if (!fieldNames.add(field.name())) {
				throw error(source, field.pos(),
						"variable " + field.name() + " is already defined in class " + made.simpleName());
			}
			made.fields.add(new JavaClass.Field(made, field.name(), type(field.type(), unit),
					field.modifiers().contains("static"), field.modifiers().contains("final"), field, null));
		}
		Set<String> signatures = new HashSet<>();
		boolean constructed = false;
		for (JavaSyntax.Method method : declaration.methods()) {
			List<JavaType> parameters = new ArrayList<>();
			for (Parameter parameter : method.parameters()) {
				parameters.add(type(java.util.Objects.requireNonNull(parameter.type()), unit));
			}
			TypeName returned = method.returnType();
			JavaType returnType = returned == null || returned.name().equals("void") ? Primitive.VOID
					: type(returned, unit);
			boolean isStatic = method.modifiers().contains("static");
			boolean isAbstract = method.body() == null;
			if (isAbstract && !made.isInterface && !method.modifiers().contains("abstract")) {
				throw error(source, method.pos(), "missing method body, or declare abstract");
			}
			if (!isAbstract && method.modifiers().contains("abstract")) {
				throw error(source, method.pos(), "abstract methods cannot have a body");
			}
			if (isAbstract && !made.isAbstract) {
				throw error(source, method.pos(),
						made.simpleName() + " is not abstract and declares the abstract method " + method.name());
			}
			JavaClass.Method member = new JavaClass.Method(made, method.name(), parameters, returnType, isStatic,
					isAbstract, method, null, false);
			if (!signatures.add(member.signature())) {
				throw error(source, method.pos(),
						"method " + member.signature() + " is already defined in class " + made.simpleName());
			}
			constructed |= member.isConstructor();
			if (made.isInterface && member.isObjectMethod()) {
				// every implementation has it already
				continue;
			}
			made.methods.add(member);
		}
		if (!made.isInterface && !constructed) {
			made.methods
				.add(new JavaClass.Method(made, "<init>", List.of(), Primitive.VOID, false, false, null, null, false));
		}
	}

	/**
	 * The class a name written in a file means, or {@code null}: a class of the file, a
	 * single-type import, a class of the file's package, of {@code java.lang}, of a
	 * package imported on demand; a qualified name is the class itself.
	 */
	@Nullable JavaClass find(String name, Unit unit) {
		if (name.indexOf('.') >= 0) {
			return qualified(name);
		}
		JavaClass declared = unit.declared().get(name);
		if (declared != null) {
			return declared;
		}
		Import imported = unit.imports().get(name);
		if (imported != null) {
			return qualified(imported.name());
		}
		if (unit.packageName() != null) {
			JavaClass sibling = qualified(unit.packageName() + "." + name);
			if (sibling != null) {
				return sibling;
			}
		}
		else if (this.classes.get(name) instanceof JavaClass sibling) {
			return sibling;
		}
		JavaClass implicit = qualified("java.lang." + name);
		if (implicit != null) {
			return implicit;
		}
		for (String packageName : unit.onDemand()) {
			JavaClass found = qualified(packageName + "." + name);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	/** {@link #find}, or the error a compiler reports. */
	JavaClass require(String name, int pos, Unit unit) {
		JavaClass found = find(name, unit);
		if (found != null) {
			return found;
		}
		Import imported = unit.imports().get(name);
		String qualified = imported != null ? imported.name() : name;
		if (qualified.startsWith("java.") || qualified.startsWith("javax.")) {
			throw unsupported(unit.source(), pos, "the class " + qualified);
		}
		throw error(unit.source(), pos, "cannot find symbol: class " + name);
	}

	/** The class of a qualified name: one declared already, or one of the library. */
	@Nullable JavaClass qualified(String name) {
		JavaClass known = this.classes.get(name);
		if (known != null || this.missing.contains(name)) {
			return known;
		}
		JavaClass intrinsic = this.library.create(name, this);
		if (intrinsic != null) {
			return intrinsic;
		}
		String path = "library/" + name.replace('.', '/') + ".java";
		String text;
		try (InputStream in = JavaClasses.class.getResourceAsStream(path)) {
			if (in == null) {
				this.missing.add(name);
				return null;
			}
			text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		String simpleName = name.substring(name.lastIndexOf('.') + 1);
		declare(JavaParser.parse(text, path, simpleName), new Source(path, text));
		return java.util.Objects.requireNonNull(this.classes.get(name), () -> path + " does not declare " + name);
	}

	/** The library class of a qualified name, which the front end itself relies on. */
	JavaClass library(String name) {
		return java.util.Objects.requireNonNull(qualified(name), () -> name + " is missing from the library");
	}

	ClassType libraryType(String name) {
		return new ClassType(library(name), List.of());
	}

	/** The type a written type means. */
	JavaType type(TypeName written, Unit unit) {
		JavaType type = switch (written.name()) {
			case "int" -> Primitive.INT;
			case "long" -> Primitive.LONG;
			case "double" -> Primitive.DOUBLE;
			case "boolean" -> Primitive.BOOLEAN;
			case "char" -> Primitive.CHAR;
			case "byte", "short", "float" ->
				throw unsupported(unit.source(), written.pos(), "the type " + written.name());
			case "void" -> throw error(unit.source(), written.pos(), "void is not allowed here");
			case "var" -> throw error(unit.source(), written.pos(), "var is not allowed here");
			default -> classType(written, unit);
		};
		for (int i = 0; i < written.dimensions(); i++) {
			type = new ArrayType(type);
		}
		return type;
	}

	private ClassType classType(TypeName written, Unit unit) {
		JavaClass declaration = require(written.name(), written.pos(), unit);
		Source source = unit.source();
		if (written.diamond()) {
			throw error(source, written.pos(), "cannot infer type arguments for " + declaration.simpleName() + " here");
		}
		if (declaration.typeParameters == 0 && !written.arguments().isEmpty()) {
			throw error(source, written.pos(), "type " + declaration.simpleName() + " does not take parameters");
		}
		if (declaration.typeParameters > 0 && written.arguments().isEmpty()) {
			throw unsupported(source, written.pos(), "the raw type " + declaration.simpleName());
		}
		if (declaration.typeParameters != written.arguments().size()) {
			throw error(source, written.pos(), "wrong number of type arguments for " + declaration.simpleName());
		}
		List<JavaType> arguments = new ArrayList<>();
		for (TypeName argument : written.arguments()) {
			JavaType type = type(argument, unit);
			if (type.isPrimitive()) {
				throw error(source, argument.pos(), "unexpected type; required: reference, found: " + type);
			}
			arguments.add(type);
		}
		ClassType type = new ClassType(declaration, List.copyOf(arguments));
		this.library.check(type, source, written.pos());
		return type;
	}

}
