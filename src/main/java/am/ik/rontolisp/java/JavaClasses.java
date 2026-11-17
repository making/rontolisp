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
import java.util.function.Function;

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
import am.ik.rontolisp.java.JavaType.TypeParameter;
import am.ik.rontolisp.java.JavaType.Variable;
import am.ik.rontolisp.java.JavaType.Wildcard;
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
	private static final List<String> LIBRARY_PACKAGES = List.of("java.lang", "java.util", "java.util.function");

	private static final String COMPARABLE = "java.lang.Comparable";

	private static final String ENUM = "java.lang.Enum";

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

	/** The local and anonymous classes, by declaration: a pass made again finds them. */
	private final Map<ClassDeclaration, JavaClass> locals = new IdentityHashMap<>();

	private final Map<String, Integer> localNumbers = new HashMap<>();

	private final Map<ClassDeclaration, Integer> numbers = new IdentityHashMap<>();

	/** The classes whose bodies' local and anonymous classes are numbered. */
	private final Set<ClassDeclaration> numbered = java.util.Collections.newSetFromMap(new IdentityHashMap<>());

	private int version;

	/** The local classes in scope where the lowering stands, by simple name. */
	@Nullable Function<String, @Nullable JavaClass> localScope;

	/**
	 * The type parameters of the method whose signature is being read or whose body is
	 * being lowered.
	 */
	List<TypeParameter> methodParameters = List.of();

	/** The interfaces a lambda or a method reference of the program is made for. */
	final Set<JavaClass> lambdaTargets = new java.util.LinkedHashSet<>();

	/**
	 * The interfaces whose lambdas are structures holding the function, because the
	 * program can tell which interface a lambda was made for.
	 */
	final Set<JavaClass> taggedLambdas = new java.util.LinkedHashSet<>();

	/**
	 * The methods of the library's Java source a pass called: only these are lowered.
	 * Kept over the passes, as the two sets after it.
	 */
	final Set<JavaClass.Method> reached = new java.util.LinkedHashSet<>();

	/** The classes of the library's Java source a pass makes an instance of. */
	final Set<JavaClass> made = new java.util.LinkedHashSet<>();

	/**
	 * The classes of the library's Java source whose static initialization a pass needs.
	 */
	final Set<JavaClass> used = new java.util.LinkedHashSet<>();

	/**
	 * Whether the program compares two strings by reference: its constant strings are
	 * then one object a text.
	 */
	boolean identicalStrings;

	private final Library library;

	/**
	 * The classes of the library that are no Java source: made by name, checked by type.
	 */
	interface Library {

		/** Makes the class of a qualified name and adds it, or answers {@code null}. */
		@Nullable JavaClass create(String name, JavaClasses classes);

		/**
		 * Tells that a class which is no Java source and an interface of the library's
		 * Java source are both in the table: the class may gain members naming it.
		 */
		void meets(JavaClass intrinsic, JavaClass named, JavaClasses classes);

		/**
		 * The interfaces of the library's Java source that a class which is no Java
		 * source implements, by qualified name: each with the class itself as its
		 * argument when it takes one.
		 */
		List<String> implemented(String name);

		/**
		 * The form a {@code native} member of the library's Java source is, or
		 * {@code null}: found by its class's name, its own and its erased parameters.
		 */
		@Nullable JavaEmitter emitter(String member);

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

	/**
	 * How many times the table changed: a class added, a local found to be read by a
	 * local class. A lowering made while it moved is made again.
	 */
	int version() {
		return this.version;
	}

	void changed() {
		this.version++;
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
		this.version++;
		if (declaration.isInterface && !declaration.intrinsic) {
			for (JavaClass known : List.copyOf(this.classes.values())) {
				implement(known, declaration);
			}
		}
		else if (declaration.intrinsic || declaration.name.equals(ENUM)) {
			for (JavaClass known : List.copyOf(this.classes.values())) {
				implement(declaration, known);
			}
		}
	}

	/**
	 * Makes a class of the library implement an interface of the library's Java source
	 * when it does: an enum's superclass and a box are {@code Comparable} to themselves,
	 * a string a {@code CharSequence} too. Only in a program that names the interface, so
	 * that every other program holds no interface it does not name.
	 */
	private void implement(JavaClass declaration, JavaClass implemented) {
		if (!implemented.isInterface || implemented.intrinsic) {
			return;
		}
		if (declaration.intrinsic) {
			this.library.meets(declaration, implemented, this);
		}
		boolean enumeration = declaration.name.equals(ENUM) && implemented.name.equals(COMPARABLE);
		if ((!enumeration
				&& !(declaration.intrinsic && this.library.implemented(declaration.name).contains(implemented.name)))
				|| declaration.interfaces.contains(implemented)) {
			return;
		}
		declaration.interfaces.add(implemented);
		if (!implemented.typeParameters.isEmpty()) {
			declaration.parameterized.put(implemented, new ClassType(implemented, List.of(enumeration
					? new Variable(declaration.typeParameters.get(0)) : new ClassType(declaration, List.of()))));
		}
		this.version++;
	}

	/**
	 * Adds the classes a file declares: every class first, so that one may name another,
	 * then what each extends and its members.
	 * @param tree the file
	 * @param source its text
	 * @return the top-level classes, in the file's order
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
		List<JavaClass> all = new ArrayList<>();
		for (ClassDeclaration declaration : tree.classes()) {
			String name = tree.packageName() == null ? declaration.name()
					: tree.packageName() + "." + declaration.name();
			if (this.classes.containsKey(name) || unit.declared().containsKey(declaration.name())) {
				throw error(source, declaration.pos(), "duplicate class: " + name);
			}
			JavaClass made = create(declaration, name, name, null, true, unit, all);
			unit.declared().put(declaration.name(), made);
			declared.add(made);
		}
		complete(all, unit);
		return declared;
	}

	/**
	 * Adds a local or an anonymous class, once: a lowering made again finds the class its
	 * first pass made.
	 * @param declaration the class's declaration
	 * @param outer the class whose body it stands in
	 * @param isStatic whether it stands in a static context
	 * @param extended the type an anonymous class is made of, or {@code null} for a local
	 * class
	 * @return the class
	 */
	JavaClass local(ClassDeclaration declaration, JavaClass outer, boolean isStatic, @Nullable ClassType extendedType) {
		JavaClass extended = extendedType == null ? null : extendedType.declaration();
		JavaClass known = this.locals.get(declaration);
		if (known != null) {
			return known;
		}
		Unit unit = unit(outer);
		String name = outer.binaryName + "$" + number(declaration, outer) + declaration.name();
		List<JavaClass> all = new ArrayList<>();
		JavaClass made = create(declaration, name, name, outer, isStatic || declaration.kind() != JavaSyntax.Kind.CLASS,
				unit, all);
		made.isLocal = true;
		made.scope = this.methodParameters;
		this.locals.put(declaration, made);
		if (extended != null) {
			if (extended.isFinal && !extended.isEnum) {
				throw error(unit.source(), declaration.pos(), "cannot inherit from final " + extended.simpleName());
			}
			if (extended.isSealed && !extended.isEnum) {
				throw error(unit.source(), declaration.pos(), "local classes must not extend sealed classes");
			}
			if (extended.isInterface) {
				made.interfaces.add(extended);
			}
			else {
				made.superclass = extended.intrinsic ? null : extended;
			}
			if (extendedType != null && !extendedType.arguments().isEmpty()) {
				made.parameterized.put(extended, extendedType);
			}
		}
		complete(all, unit);
		return made;
	}

	/**
	 * The number in a local or an anonymous class's name: the classes of one simple name
	 * declared in a class's body are counted in the order they are written, as a compiler
	 * names them.
	 */
	private int number(ClassDeclaration declaration, JavaClass outer) {
		Integer known = this.numbers.get(declaration);
		if (known == null && outer.declaration != null && this.numbered.add(outer.declaration)) {
			ClassDeclaration around = outer.declaration;
			List<Object> members = new ArrayList<>();
			members.addAll(around.constants());
			members.addAll(around.fields());
			members.addAll(around.methods());
			members.addAll(around.initializers());
			members.sort(java.util.Comparator.comparingInt(member -> switch (member) {
				case JavaSyntax.EnumConstant constant -> constant.pos();
				case JavaSyntax.Field field -> field.pos();
				case JavaSyntax.Method method -> method.pos();
				case JavaSyntax.Initializer block -> block.pos();
				default -> 0;
			}));
			List<ClassDeclaration> found = new ArrayList<>();
			for (Object member : members) {
				declared(member, found);
			}
			for (ClassDeclaration local : found) {
				this.numbers.put(local,
						this.localNumbers.merge(outer.binaryName + "$" + local.name(), 1, Integer::sum));
			}
			known = this.numbers.get(declaration);
		}
		return known != null ? known
				: this.localNumbers.merge(outer.binaryName + "$" + declaration.name(), 1, Integer::sum);
	}

	/**
	 * The local and anonymous classes declared in a part of a class's body, in the order
	 * written; the ones declared inside them are theirs to count.
	 */
	private static void declared(@Nullable Object node, List<ClassDeclaration> into) {
		switch (node) {
			case null -> {
			}
			case List<?> nodes -> nodes.forEach(each -> declared(each, into));
			case JavaSyntax.EnumConstant constant -> {
				declared(constant.arguments(), into);
				declared(constant.body(), into);
			}
			case ClassDeclaration local -> into.add(local);
			case JavaSyntax.Field field -> declared(field.initializer(), into);
			case JavaSyntax.Method method -> declared(method.body(), into);
			case JavaSyntax.Initializer block -> declared(block.body(), into);
			case JavaSyntax.New creation -> {
				declared(creation.outer(), into);
				declared(creation.arguments(), into);
				declared(creation.body(), into);
			}
			case JavaSyntax.LocalClass local -> into.add(local.declaration());
			case JavaSyntax.Select select -> declared(select.target(), into);
			case JavaSyntax.QualifiedThis self -> {
			}
			case JavaSyntax.Call call -> {
				declared(call.target(), into);
				declared(call.arguments(), into);
			}
			case JavaSyntax.ConstructorCall call -> declared(call.arguments(), into);
			case JavaSyntax.NewArray creation -> {
				declared(creation.lengths(), into);
				declared(creation.initializer(), into);
			}
			case JavaSyntax.ArrayInitializer initializer -> declared(initializer.elements(), into);
			case JavaSyntax.Index index -> {
				declared(index.array(), into);
				declared(index.index(), into);
			}
			case JavaSyntax.Unary unary -> declared(unary.operand(), into);
			case JavaSyntax.Binary binary -> {
				declared(binary.left(), into);
				declared(binary.right(), into);
			}
			case JavaSyntax.Assign assignment -> {
				declared(assignment.target(), into);
				declared(assignment.value(), into);
			}
			case JavaSyntax.Conditional conditional -> {
				declared(conditional.condition(), into);
				declared(conditional.whenTrue(), into);
				declared(conditional.whenFalse(), into);
			}
			case JavaSyntax.Cast cast -> declared(cast.operand(), into);
			case JavaSyntax.InstanceOf test -> declared(test.operand(), into);
			case JavaSyntax.Lambda lambda -> {
				declared(lambda.expression(), into);
				declared(lambda.block(), into);
			}
			case JavaSyntax.MethodReference reference -> declared(reference.target(), into);
			case JavaSyntax.Switch block -> {
				declared(block.selector(), into);
				for (JavaSyntax.SwitchCase written : block.cases()) {
					declared(written.constants(), into);
					declared(written.guard(), into);
					declared(written.body(), into);
					declared(written.value(), into);
				}
			}
			case JavaSyntax.LocalVariable variable ->
				variable.declarators().forEach(declarator -> declared(declarator.initializer(), into));
			case JavaSyntax.ExpressionStatement statement -> declared(statement.expression(), into);
			case JavaSyntax.If branch -> {
				declared(branch.condition(), into);
				declared(branch.then(), into);
				declared(branch.otherwise(), into);
			}
			case JavaSyntax.While loop -> {
				declared(loop.condition(), into);
				declared(loop.body(), into);
			}
			case JavaSyntax.DoWhile loop -> {
				declared(loop.body(), into);
				declared(loop.condition(), into);
			}
			case JavaSyntax.For loop -> {
				declared(loop.initializers(), into);
				declared(loop.condition(), into);
				declared(loop.updates(), into);
				declared(loop.body(), into);
			}
			case JavaSyntax.ForEach loop -> {
				declared(loop.iterable(), into);
				declared(loop.body(), into);
			}
			case JavaSyntax.Return exit -> declared(exit.value(), into);
			case JavaSyntax.Yield exit -> declared(exit.value(), into);
			case JavaSyntax.Throw exit -> declared(exit.value(), into);
			case JavaSyntax.Labeled labeled -> declared(labeled.body(), into);
			case JavaSyntax.Block block -> declared(block.statements(), into);
			case JavaSyntax.Try attempt -> {
				declared(attempt.body(), into);
				attempt.catches().forEach(clause -> declared(clause.body(), into));
				declared(attempt.finallyBlock(), into);
			}
			default -> {
			}
		}
	}

	private JavaClass create(ClassDeclaration declaration, String name, String binaryName, @Nullable JavaClass outer,
			boolean isStatic, Unit unit, List<JavaClass> all) {
		Set<String> modifiers = declaration.modifiers();
		boolean isInterface = declaration.isInterface();
		JavaClass made = new JavaClass(name, binaryName, declaration.name(), isInterface,
				isInterface || modifiers.contains("abstract"), false, declaration, unit.source());
		for (JavaSyntax.TypeParameter parameter : declaration.typeParameters()) {
			TypeParameter declared = new TypeParameter(parameter.name(), false, false);
			declared.owner = made;
			made.typeParameters.add(declared);
		}
		made.outer = outer;
		made.isStatic = isStatic;
		made.isEnum = declaration.kind() == JavaSyntax.Kind.ENUM;
		made.isRecord = declaration.kind() == JavaSyntax.Kind.RECORD;
		made.isSealed = modifiers.contains("sealed");
		made.isFinal = modifiers.contains("final") || made.isRecord
				|| (made.isEnum && declaration.constants().stream().noneMatch(constant -> constant.body() != null));
		this.units.put(made, unit);
		register(made);
		all.add(made);
		for (ClassDeclaration nested : declaration.nested()) {
			if (made.members.containsKey(nested.name())) {
				throw error(unit.source(), nested.pos(), "duplicate class: " + nested.name());
			}
			boolean nestedStatic = nested.modifiers().contains("static") || nested.kind() != JavaSyntax.Kind.CLASS
					|| isInterface;
			made.members.put(nested.name(), create(nested, name + "." + nested.name(), binaryName + "$" + nested.name(),
					made, nestedStatic, unit, all));
		}
		return made;
	}

	/** What each new class extends, what a sealed one permits, then the members. */
	private void complete(List<JavaClass> all, Unit unit) {
		Source source = unit.source();
		for (JavaClass made : all) {
			bounds(made.typeParameters, declaration(made).typeParameters(), made, unit);
		}
		for (JavaClass made : all) {
			supertypes(made, unit);
		}
		for (JavaClass made : all) {
			for (JavaClass up = made.superclass; up != null; up = up.superclass) {
				if (up == made) {
					throw error(source, declaration(made).pos(), "cyclic inheritance involving " + made.name);
				}
			}
			List<JavaClass> above = new ArrayList<>(made.interfaces);
			if (made.superclass != null) {
				above.add(made.superclass);
			}
			for (JavaClass parent : above) {
				if (parent.isFinal && !(parent.isEnum && made.isLocal)) {
					throw error(source, declaration(made).pos(), "cannot inherit from final " + parent.simpleName());
				}
				if (!parent.isSealed || parent.isEnum) {
					continue;
				}
				if (declaration(parent).permits().isEmpty()) {
					if (unit(parent) != unit) {
						throw error(source, declaration(made).pos(),
								"class is not allowed to extend sealed class: " + parent.simpleName());
					}
					parent.permits.add(made);
				}
				else if (!parent.permits.contains(made)) {
					throw error(source, declaration(made).pos(), "class is not allowed to extend sealed class: "
							+ parent.simpleName() + " (as it is not listed in its 'permits' clause)");
				}
				Set<String> modifiers = declaration(made).modifiers();
				if (!made.isFinal && !made.isSealed && !modifiers.contains("non-sealed")) {
					throw error(source, declaration(made).pos(), "sealed, non-sealed or final modifiers expected");
				}
			}
		}
		for (JavaClass made : all) {
			members(made, unit);
		}
	}

	private static ClassDeclaration declaration(JavaClass made) {
		return java.util.Objects.requireNonNull(made.declaration);
	}

	/** The bounds of declared type parameters, each of which may name the others. */
	private void bounds(List<TypeParameter> parameters, List<JavaSyntax.TypeParameter> written,
			@Nullable JavaClass context, Unit unit) {
		for (int i = 0; i < written.size(); i++) {
			List<JavaType> bounds = new ArrayList<>();
			for (TypeName bound : written.get(i).bounds()) {
				JavaType type = type(bound, context, unit);
				if (!(type instanceof ClassType) && !(type instanceof Variable)) {
					throw error(unit.source(), bound.pos(), "unexpected type; required: class, found: " + type);
				}
				bounds.add(type);
			}
			parameters.get(i).bounds = List.copyOf(bounds);
		}
	}

	/** A type a class extends or implements: a class, with the arguments written. */
	private ClassType supertype(TypeName written, JavaClass made, Unit unit) {
		JavaType type = resolve(written, made.outer, made, unit);
		if (!(type instanceof ClassType named)) {
			throw error(unit.source(), written.pos(), "unexpected type; required: class, found: " + type);
		}
		for (JavaType argument : named.arguments()) {
			if (argument instanceof Wildcard) {
				throw error(unit.source(), written.pos(),
						"unexpected type; required: class or interface without bounds, found: " + argument);
			}
		}
		if (!named.arguments().isEmpty()) {
			made.parameterized.put(named.declaration(), named);
		}
		return named;
	}

	private void supertypes(JavaClass made, Unit unit) {
		ClassDeclaration declaration = declaration(made);
		Source source = unit.source();
		TypeName extended = declaration.superclass();
		if (extended != null) {
			JavaClass superclass = supertype(extended, made, unit).declaration();
			if (superclass.isInterface) {
				throw error(source, extended.pos(), "no interface expected here");
			}
			if (superclass.intrinsic && !superclass.name.equals("java.lang.Object")) {
				throw unsupported(source, extended.pos(), "a class extending " + superclass.name);
			}
			if (superclass.isEnum) {
				throw error(source, extended.pos(), "cannot inherit from final " + superclass.simpleName());
			}
			if (superclass.hasOuterInstance() && !superclass.isLocal
					&& !(made.hasOuterInstance() && encloses(superclass.outer, made))) {
				throw unsupported(source, extended.pos(), "a class extending the inner class " + superclass.simpleName()
						+ " from outside its outer class");
			}
			made.superclass = superclass.intrinsic ? null : superclass;
		}
		else if (made.isEnum) {
			made.superclass = library("java.lang.Enum");
			made.parameterized.put(made.superclass, new ClassType(made.superclass, List.of(made.self())));
		}
		else if (made.isRecord) {
			made.superclass = library("java.lang.Record");
		}
		for (TypeName implemented : declaration.interfaces()) {
			JavaClass type = supertype(implemented, made, unit).declaration();
			if (!type.isInterface) {
				throw error(source, implemented.pos(), "interface expected here");
			}
			if (type.intrinsic) {
				throw unsupported(source, implemented.pos(), "a class implementing " + type.name);
			}
			made.interfaces.add(type);
		}
		for (TypeName permitted : declaration.permits()) {
			if (!made.isSealed) {
				throw error(source, permitted.pos(), "invalid permits clause: " + made.simpleName() + " is not sealed");
			}
			made.permits.add(require(permitted.name(), permitted.pos(), made, unit));
		}
	}

	/**
	 * Whether a class is, or extends, one of the classes another's declaration stands in.
	 */
	private static boolean encloses(@Nullable JavaClass outer, JavaClass inner) {
		for (JavaClass up = inner.outer; up != null; up = up.outer) {
			if (outer != null && up.isSubclassOf(outer)) {
				return true;
			}
		}
		return false;
	}

	private static TypeName simpleType(JavaClass made, int pos, int dimensions) {
		return new TypeName(pos, made.name, List.of(), false, dimensions);
	}

	private void members(JavaClass made, Unit unit) {
		ClassDeclaration declaration = declaration(made);
		Source source = unit.source();
		Set<String> fieldNames = new HashSet<>();
		List<JavaSyntax.Method> methods = new ArrayList<>(declaration.methods());
		ClassType self = made.self();
		// an enum's constants: static fields made once, by the constructor the arguments
		// pick, behind the name and the ordinal every constructor takes first
		int ordinal = 0;
		for (JavaSyntax.EnumConstant constant : declaration.constants()) {
			if (!fieldNames.add(constant.name())) {
				throw error(source, constant.pos(),
						"variable " + constant.name() + " is already defined in class " + made.simpleName());
			}
			JavaClass.Field field = new JavaClass.Field(made, constant.name(), self, true, true, null, null);
			List<JavaSyntax.Expression> arguments = new ArrayList<>();
			arguments.add(new JavaSyntax.StringLiteral(constant.pos(), constant.name()));
			arguments.add(new JavaSyntax.IntLiteral(constant.pos(), ordinal++, false));
			arguments.addAll(constant.arguments());
			field.initializer = new JavaSyntax.New(constant.pos(), simpleType(made, constant.pos(), 0), arguments,
					constant.body(), null);
			field.pos = constant.pos();
			field.isConstant = true;
			made.fields.add(field);
		}
		// a record's components: a private final field, an accessor and the canonical
		// constructor each, unless the record declares its own
		for (Parameter component : declaration.components()) {
			if (!fieldNames.add(component.name())) {
				throw error(source, component.pos(), "record component " + component.name() + " is already defined");
			}
			JavaClass.Field field = new JavaClass.Field(made, component.name(),
					type(java.util.Objects.requireNonNull(component.type()), made, unit), false, true, null, null);
			field.pos = component.pos();
			field.isPrivate = true;
			made.fields.add(field);
		}
		if (made.isRecord) {
			methods = recordMethods(made, declaration, methods, unit);
		}
		for (JavaSyntax.Field field : declaration.fields()) {
			if (!fieldNames.add(field.name())) {
				throw error(source, field.pos(),
						"variable " + field.name() + " is already defined in class " + made.simpleName());
			}
			boolean isStatic = field.modifiers().contains("static") || made.isInterface;
			if (made.isRecord && !isStatic) {
				throw error(source, field.pos(), "field declaration must be static");
			}
			made.fields.add(new JavaClass.Field(made, field.name(), type(field.type(), made, unit), isStatic,
					field.modifiers().contains("final") || made.isInterface, field, null));
		}
		Set<String> signatures = new HashSet<>();
		boolean constructed = false;
		for (JavaSyntax.Method method : methods) {
			List<JavaType> parameters = new ArrayList<>();
			boolean isConstructor = method.name().equals("<init>");
			if (made.isEnum && isConstructor) {
				parameters.add(libraryType("java.lang.String"));
				parameters.add(Primitive.INT);
			}
			List<TypeParameter> typeParameters = new ArrayList<>();
			for (JavaSyntax.TypeParameter parameter : method.typeParameters()) {
				typeParameters.add(new TypeParameter(parameter.name(), true, false));
			}
			List<TypeParameter> around = this.methodParameters;
			this.methodParameters = typeParameters;
			JavaType returnType;
			try {
				bounds(typeParameters, method.typeParameters(), made, unit);
				for (Parameter parameter : method.parameters()) {
					parameters.add(type(java.util.Objects.requireNonNull(parameter.type()), made, unit));
				}
				TypeName returned = method.returnType();
				returnType = returned == null || returned.name().equals("void") ? Primitive.VOID
						: type(returned, made, unit);
			}
			finally {
				this.methodParameters = around;
			}
			Set<String> modifiers = method.modifiers();
			boolean isStatic = modifiers.contains("static");
			JavaEmitter emitter = null;
			if (modifiers.contains("native")) {
				StringBuilder member = new StringBuilder(made.name).append('.').append(method.name()).append('(');
				for (int i = 0; i < parameters.size(); i++) {
					member.append(i > 0 ? "," : "").append(parameters.get(i).erasure());
				}
				emitter = this.library.emitter(member.append(')').toString());
				if (emitter == null || method.body() != null) {
					throw new IllegalStateException(member + " is native and has no form, or has a body");
				}
			}
			boolean isAbstract = method.body() == null && emitter == null;
			if (made.isInterface) {
				boolean bodied = modifiers.contains("default") || isStatic || modifiers.contains("private");
				if (!isAbstract && !bodied && emitter == null) {
					throw error(source, method.pos(), "interface abstract methods cannot have body");
				}
				if (isAbstract && bodied) {
					throw error(source, method.pos(), "missing method body, or declare abstract");
				}
			}
			else if (modifiers.contains("default")) {
				throw error(source, method.pos(), "modifier default not allowed here");
			}
			if (isAbstract && !made.isInterface && !modifiers.contains("abstract")) {
				throw error(source, method.pos(), "missing method body, or declare abstract");
			}
			if (!isAbstract && modifiers.contains("abstract")) {
				throw error(source, method.pos(), "abstract methods cannot have a body");
			}
			if (isAbstract && made.isEnum) {
				// every constant of such an enum has a body of its own
				made.isAbstract = true;
			}
			if (isAbstract && !made.isAbstract) {
				throw error(source, method.pos(),
						made.simpleName() + " is not abstract and declares the abstract method " + method.name());
			}
			JavaClass.Method member = new JavaClass.Method(made, method.name(), parameters, returnType, isStatic,
					isAbstract, method, emitter, false);
			member.typeParameters = List.copyOf(typeParameters);
			if (made.isEnum && isConstructor) {
				member.hidden = 2;
				member.isPrivate = true;
			}
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
		if (made.isLocal && declaration.name().isEmpty()) {
			// an anonymous class: its constructors are made where it is created, one for
			// each constructor of its superclass an instance creation picks
			return;
		}
		if (!made.isInterface && !constructed) {
			JavaClass.Method constructor = new JavaClass.Method(made, "<init>",
					made.isEnum ? List.of(libraryType("java.lang.String"), Primitive.INT) : List.of(), Primitive.VOID,
					false, false, null, null, false);
			constructor.hidden = made.isEnum ? 2 : 0;
			made.methods.add(constructor);
		}
	}

	/**
	 * The methods of a record: its own, the canonical constructor, the accessors and
	 * {@code toString}, {@code equals} and {@code hashCode} it does not declare.
	 */
	private List<JavaSyntax.Method> recordMethods(JavaClass made, ClassDeclaration declaration,
			List<JavaSyntax.Method> declared, Unit unit) {
		int at = declaration.pos();
		List<Parameter> components = declaration.components();
		List<JavaSyntax.Statement> stores = new ArrayList<>();
		for (Parameter component : components) {
			stores.add(new JavaSyntax.ExpressionStatement(at,
					new JavaSyntax.Assign(at, "=", new JavaSyntax.Select(at, new JavaSyntax.This(at), component.name()),
							new JavaSyntax.Name(at, component.name()))));
		}
		List<JavaType> componentTypes = new ArrayList<>();
		for (JavaClass.Field field : made.fields) {
			componentTypes.add(field.type);
		}
		List<JavaSyntax.Method> methods = new ArrayList<>();
		boolean canonical = false;
		Set<String> names = new HashSet<>();
		for (JavaSyntax.Method method : declared) {
			if (method.compact()) {
				if (canonical) {
					throw error(unit.source(), method.pos(), "constructor is already defined in the record");
				}
				List<JavaSyntax.Statement> body = new ArrayList<>(
						java.util.Objects.requireNonNull(method.body()).statements());
				body.addAll(stores);
				methods.add(new JavaSyntax.Method(method.pos(), method.modifiers(), null, "<init>", components,
						new JavaSyntax.Block(method.pos(), body)));
				canonical = true;
				continue;
			}
			if (method.name().equals("<init>")) {
				List<JavaType> parameters = new ArrayList<>();
				for (Parameter parameter : method.parameters()) {
					parameters.add(type(java.util.Objects.requireNonNull(parameter.type()), made, unit));
				}
				canonical |= parameters.equals(componentTypes);
			}
			else if (method.parameters().isEmpty()) {
				names.add(method.name());
			}
			else if (method.name().equals("equals") && method.parameters().size() == 1) {
				names.add("equals");
			}
			methods.add(method);
		}
		if (!canonical) {
			methods.add(new JavaSyntax.Method(at, Set.of("public"), null, "<init>", components,
					new JavaSyntax.Block(at, stores)));
		}
		for (Parameter component : components) {
			if (!names.contains(component.name())) {
				methods.add(new JavaSyntax.Method(at, Set.of("public"), component.type(), component.name(), List.of(),
						new JavaSyntax.Block(at, List.of(new JavaSyntax.Return(at,
								new JavaSyntax.Select(at, new JavaSyntax.This(at), component.name()))))));
			}
		}
		String simple = made.simpleName();
		StringBuilder text = new StringBuilder();
		if (!names.contains("toString")) {
			text.append("public String toString() { return \"").append(simple).append("[\"");
			for (int i = 0; i < components.size(); i++) {
				String name = components.get(i).name();
				text.append(" + \"").append(i > 0 ? ", " : "").append(name).append("=\" + this.").append(name);
			}
			text.append(" + \"]\"; }\n");
		}
		if (!names.contains("equals")) {
			text.append("public boolean equals(Object $o) { return $o instanceof ")
				.append(simple)
				.append(components.isEmpty() ? "" : " $that");
			for (int i = 0; i < components.size(); i++) {
				String name = components.get(i).name();
				JavaType type = componentTypes.get(i);
				String mine = "this." + name;
				String theirs = "$that." + name;
				text.append(" && ");
				if (type.isFloating()) {
					// a float widens to the double of its value: one comparison for both
					text.append("Double.compare(").append(mine).append(", ").append(theirs).append(") == 0");
				}
				else if (type.isPrimitive()) {
					text.append(mine).append(" == ").append(theirs);
				}
				else {
					text.append("(")
						.append(mine)
						.append(" == null ? ")
						.append(theirs)
						.append(" == null : ")
						.append(mine)
						.append(".equals(")
						.append(theirs)
						.append("))");
				}
			}
			text.append("; }\n");
		}
		if (!names.contains("hashCode")) {
			text.append("public int hashCode() { int $h = 0;");
			for (int i = 0; i < components.size(); i++) {
				String mine = "this." + components.get(i).name();
				JavaType type = componentTypes.get(i);
				String hash = type == Primitive.INT || type.isBelowInt() ? mine
						: type == Primitive.LONG ? "Long.hashCode(" + mine + ")"
								: type == Primitive.FLOAT ? "rontolisp.internal.Support.floatHash(" + mine + ")"
										: type == Primitive.DOUBLE ? "Double.hashCode(" + mine + ")"
												: type == Primitive.BOOLEAN ? "Boolean.hashCode(" + mine + ")"
														: "(" + mine + " == null ? 0 : " + mine + ".hashCode())";
				text.append(" $h = 31 * $h + ").append(hash).append(";");
			}
			text.append(" return $h; }\n");
		}
		methods.addAll(JavaParser.synthetic(text.toString(), simple, JavaSyntax.Kind.RECORD, at));
		return methods;
	}

	/**
	 * Adds an enum's {@code values()} or {@code valueOf(String)}, and the array both
	 * read, where a program first names one: the second throws, and a program that does
	 * not name it holds no exception class for it.
	 * @param made the enum
	 * @param name {@code values} or {@code valueOf}
	 */
	void enumMethod(JavaClass made, String name) {
		if (!made.isEnum || made.declaration == null
				|| made.methods.stream().anyMatch(method -> method.name.equals(name))) {
			return;
		}
		ClassDeclaration declaration = made.declaration;
		Unit unit = unit(made);
		int at = declaration.pos();
		if (made.fields.stream().noneMatch(field -> field.name.equals("$VALUES"))) {
			List<JavaSyntax.Expression> constants = new ArrayList<>();
			for (JavaSyntax.EnumConstant constant : declaration.constants()) {
				constants.add(new JavaSyntax.Name(at, constant.name()));
			}
			JavaClass.Field values = new JavaClass.Field(made, "$VALUES", new ArrayType(made.self()), true, true, null,
					null);
			values.initializer = new JavaSyntax.ArrayInitializer(at, constants);
			// set right after the constants: an initializer below them may ask for
			// values()
			values.pos = declaration.constants().isEmpty() ? at
					: declaration.constants().get(declaration.constants().size() - 1).pos();
			values.isPrivate = true;
			values.frozen = true;
			made.fields.add(values);
		}
		String type = made.simpleName();
		String text = name.equals("values") ? "public static " + type + "[] values() { return $VALUES.clone(); }\n"
				: "public static " + type + " valueOf(String name) { for (" + type
						+ " constant : $VALUES) { if (constant.name().equals(name)) { return constant; } }"
						+ " throw new IllegalArgumentException(\"No enum constant " + made.name + ".\" + name); }\n";
		for (JavaSyntax.Method method : JavaParser.synthetic(text, type, JavaSyntax.Kind.ENUM, at)) {
			List<JavaType> parameters = new ArrayList<>();
			for (Parameter parameter : method.parameters()) {
				parameters.add(type(java.util.Objects.requireNonNull(parameter.type()), made, unit));
			}
			made.methods.add(new JavaClass.Method(made, method.name(), parameters,
					type(java.util.Objects.requireNonNull(method.returnType()), made, unit), true, false, method, null,
					false));
		}
		this.version++;
	}

	/** The member type of a name a class has: its own, or one it inherits. */
	private static @Nullable JavaClass member(JavaClass declaration, String name, Set<JavaClass> seen) {
		if (!seen.add(declaration)) {
			return null;
		}
		JavaClass found = declaration.members.get(name);
		if (found == null && declaration.superclass != null) {
			found = member(declaration.superclass, name, seen);
		}
		for (int i = 0; found == null && i < declaration.interfaces.size(); i++) {
			found = member(declaration.interfaces.get(i), name, seen);
		}
		return found;
	}

	/**
	 * The class a name written in a class's body means, or {@code null}: a local class in
	 * scope, the class itself or a member type of it or of a class around it, a class of
	 * the file, a single-type import, a class of the file's package, of
	 * {@code java.lang}, of a package imported on demand. A qualified name is a class and
	 * the member types under it.
	 */
	@Nullable JavaClass find(String name, @Nullable JavaClass context, Unit unit) {
		int dot = name.indexOf('.');
		if (dot >= 0) {
			JavaClass whole = qualified(name);
			if (whole != null) {
				return whole;
			}
			// the longest prefix that is a class, then member types
			for (int end = name.lastIndexOf('.'); end > 0; end = name.lastIndexOf('.', end - 1)) {
				String prefix = name.substring(0, end);
				JavaClass outer = prefix.indexOf('.') < 0 ? find(prefix, context, unit) : qualified(prefix);
				if (outer == null) {
					continue;
				}
				for (String segment : name.substring(end + 1).split("\\.")) {
					outer = outer == null ? null : member(outer, segment, new HashSet<>());
				}
				return outer;
			}
			return null;
		}
		JavaClass local = this.localScope == null ? null : this.localScope.apply(name);
		if (local != null) {
			return local;
		}
		for (JavaClass around = context; around != null; around = around.outer) {
			if (around.simpleName().equals(name)) {
				return around;
			}
			JavaClass member = member(around, name, new HashSet<>());
			if (member != null) {
				return member;
			}
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
	JavaClass require(String name, int pos, @Nullable JavaClass context, Unit unit) {
		JavaClass found = find(name, context, unit);
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
		String path = JavaParser.LIBRARY + name.replace('.', '/') + ".java";
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
		// a class of the library names no local class of the program
		Function<String, @Nullable JavaClass> scope = this.localScope;
		List<TypeParameter> parameters = this.methodParameters;
		this.localScope = null;
		this.methodParameters = List.of();
		try {
			declare(JavaParser.parse(text, path, simpleName), new Source(path, text));
		}
		finally {
			this.localScope = scope;
			this.methodParameters = parameters;
		}
		return java.util.Objects.requireNonNull(this.classes.get(name), () -> path + " does not declare " + name);
	}

	/** The library class of a qualified name, which the front end itself relies on. */
	JavaClass library(String name) {
		return java.util.Objects.requireNonNull(qualified(name), () -> name + " is missing from the library");
	}

	ClassType libraryType(String name) {
		return new ClassType(library(name), List.of());
	}

	/** The type a type written in a class's body means. */
	JavaType type(TypeName written, @Nullable JavaClass context, Unit unit) {
		return resolve(written, context, context, unit);
	}

	/**
	 * The type a type as written means.
	 * @param context the class a class's name is looked for from
	 * @param variables the class a type variable's name is looked for from: the class
	 * itself where its own header is read, which names no member type of its own
	 */
	private JavaType resolve(TypeName written, @Nullable JavaClass context, @Nullable JavaClass variables, Unit unit) {
		JavaType type = switch (written.name()) {
			case "int" -> Primitive.INT;
			case "long" -> Primitive.LONG;
			case "double" -> Primitive.DOUBLE;
			case "boolean" -> Primitive.BOOLEAN;
			case "char" -> Primitive.CHAR;
			case "byte" -> Primitive.BYTE;
			case "short" -> Primitive.SHORT;
			case "float" -> Primitive.FLOAT;
			case "void" -> throw error(unit.source(), written.pos(), "void is not allowed here");
			case "var" -> throw error(unit.source(), written.pos(), "var is not allowed here");
			case "?", "? extends", "? super" ->
				throw error(unit.source(), written.pos(), "a wildcard stands only as a type argument");
			default -> named(written, context, variables, unit);
		};
		for (int i = 0; i < written.dimensions(); i++) {
			type = new ArrayType(type);
		}
		return type;
	}

	/**
	 * The type parameter a simple name means where a type is written, or {@code null}.
	 */
	private @Nullable TypeParameter typeVariable(String name, @Nullable JavaClass context) {
		for (TypeParameter parameter : this.methodParameters) {
			if (parameter.name.equals(name)) {
				return parameter;
			}
		}
		for (JavaClass around = context; around != null; around = around.outer) {
			for (TypeParameter parameter : around.typeParameters) {
				if (parameter.name.equals(name)) {
					return parameter;
				}
			}
			for (TypeParameter parameter : around.scope) {
				if (parameter.name.equals(name)) {
					return parameter;
				}
			}
			if (around.isStatic && !around.isLocal) {
				// a static member reads no type parameter of the class around it
				return null;
			}
		}
		return null;
	}

	private JavaType named(TypeName written, @Nullable JavaClass context, @Nullable JavaClass variables, Unit unit) {
		Source source = unit.source();
		JavaClass local = written.name().indexOf('.') < 0 && this.localScope != null
				? this.localScope.apply(written.name()) : null;
		TypeParameter parameter = written.name().indexOf('.') < 0 && local == null
				? typeVariable(written.name(), variables) : null;
		if (parameter != null) {
			if (!written.arguments().isEmpty() || written.diamond()) {
				throw error(source, written.pos(),
						"unexpected type; required: class, found: type parameter " + parameter.name);
			}
			return new Variable(parameter);
		}
		JavaClass declaration = require(written.name(), written.pos(), context, unit);
		if (written.diamond()) {
			throw error(source, written.pos(), "cannot infer type arguments for " + declaration.simpleName() + " here");
		}
		int declared = declaration.typeParameters.size();
		if (written.arguments().isEmpty()) {
			// a class with no parameter, or a raw type
			return new ClassType(declaration, List.of());
		}
		if (declared == 0) {
			throw error(source, written.pos(), "type " + declaration.simpleName() + " does not take parameters");
		}
		if (declared != written.arguments().size()) {
			throw error(source, written.pos(), "wrong number of type arguments; required " + declared);
		}
		List<JavaType> arguments = new ArrayList<>();
		for (TypeName argument : written.arguments()) {
			JavaType type;
			if (argument.isWildcard()) {
				JavaType bound = argument.arguments().isEmpty() ? null
						: resolve(argument.arguments().get(0), context, variables, unit);
				if (bound != null && bound.isPrimitive()) {
					throw error(source, argument.pos(), "unexpected type; required: reference, found: " + bound);
				}
				type = argument.name().equals("? super") ? new Wildcard(null, bound) : new Wildcard(bound, null);
			}
			else {
				type = resolve(argument, context, variables, unit);
				if (type.isPrimitive()) {
					throw error(source, argument.pos(), "unexpected type; required: reference, found: " + type);
				}
			}
			arguments.add(type);
		}
		return new ClassType(declaration, List.copyOf(arguments));
	}

}
