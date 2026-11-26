package am.ik.rontolisp.java;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.GenericDeclaration;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.abort;

/**
 * Generates the members of the JDK's classes as the Java subset's reflection answers them
 * ({@code jdk-members.txt}, {@link JavaJdkMembers}) from the running JDK's public
 * {@code java.lang.reflect}, and pins the shipped file to them: a class the library gains
 * fails here, and the message names the regenerated file to copy over the shipped one.
 *
 * <p>
 * Everything comes from running the JDK, nothing from reading it
 * ({@code .kb/java-frontend-provenance.md}): the classes are the library's own -- the
 * intrinsic ones and the files of a JDK's packages, with the member classes a file
 * declares --, each with the public and protected methods, fields and constructors the
 * JDK reflects (names, types, modifiers, the types thrown), sorted, the classes above
 * them, and by name the types a member names that the library lacks. {@code getMethods()}
 * and {@code getFields()} are not written: the table answers them by the published
 * algorithm, held here to the JDK's answers for every class.
 */
class JavaJdkMembersTest {

	private static final String SHIPPED = "src/main/resources/am/ik/rontolisp/java/jdk-members.txt";

	private static final String LIBRARY = "src/main/resources/am/ik/rontolisp/java/library";

	/** The library's intrinsic classes, which have no file. */
	private static final List<String> INTRINSIC = List.of("java.lang.Object", "java.lang.String",
			"java.lang.StringBuilder", "java.lang.StringBuffer", "java.lang.Math", "java.lang.StrictMath",
			"java.lang.Number", "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
			"java.lang.Byte", "java.lang.Short", "java.lang.Boolean", "java.lang.Character", "java.lang.System",
			"java.nio.charset.StandardCharsets", "java.lang.IO");

	/** A member class a file declares, by its simple name. */
	private static final Pattern DECLARED = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z]\\w*)");

	private static boolean api(int modifiers) {
		return (modifiers & (Modifier.PUBLIC | Modifier.PROTECTED)) != 0;
	}

	private static String names(Class<?>[] types) {
		if (types.length == 0) {
			return "-";
		}
		List<String> names = new ArrayList<>();
		for (Class<?> type : types) {
			names.add(type.getName());
		}
		return String.join(",", names);
	}

	private static List<Method> methods(Class<?> type) {
		List<Method> found = new ArrayList<>();
		for (Method method : type.getDeclaredMethods()) {
			if (api(method.getModifiers())) {
				found.add(method);
			}
		}
		found.sort(Comparator.comparing(Method::getName)
			.thenComparing(method -> names(method.getParameterTypes()))
			.thenComparing(method -> method.getReturnType().getName()));
		return found;
	}

	private static List<Field> fields(Class<?> type) {
		List<Field> found = new ArrayList<>();
		for (Field field : type.getDeclaredFields()) {
			if (api(field.getModifiers())) {
				found.add(field);
			}
		}
		found.sort(Comparator.comparing(Field::getName));
		return found;
	}

	private static List<Constructor<?>> constructors(Class<?> type) {
		List<Constructor<?>> found = new ArrayList<>();
		for (Constructor<?> constructor : type.getDeclaredConstructors()) {
			if (api(constructor.getModifiers())) {
				found.add(constructor);
			}
		}
		found.sort(Comparator.comparing(constructor -> names(constructor.getParameterTypes())));
		return found;
	}

	/**
	 * The library's classes of the JDK, by binary name: the intrinsic ones and each file
	 * of a JDK's package whose class is public there, with the public and protected
	 * member classes the file declares.
	 */
	private static TreeMap<String, Class<?>> libraryClasses() throws IOException {
		TreeMap<String, Class<?>> found = new TreeMap<>();
		for (String name : INTRINSIC) {
			found.put(name, load(name));
		}
		Path root = Path.of(LIBRARY);
		List<Path> files;
		try (Stream<Path> walked = Files.walk(root)) {
			files = walked.filter(file -> file.toString().endsWith(".java")).sorted().toList();
		}
		for (Path file : files) {
			String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
			if (!relative.startsWith("java/") && !relative.startsWith("javax/") && !relative.startsWith("com/sun/")) {
				continue;
			}
			Class<?> type;
			try {
				type = Class.forName(relative.substring(0, relative.length() - 5).replace('/', '.'), false,
						JavaJdkMembersTest.class.getClassLoader());
			}
			catch (ClassNotFoundException ex) {
				// a class of the library's own under a JDK's package
				continue;
			}
			if (!Modifier.isPublic(type.getModifiers())) {
				continue;
			}
			Set<String> declared = new HashSet<>();
			Matcher matcher = DECLARED.matcher(Files.readString(file));
			while (matcher.find()) {
				declared.add(matcher.group(1));
			}
			add(found, type, declared);
		}
		return found;
	}

	private static void add(TreeMap<String, Class<?>> found, Class<?> type, Set<String> declared) {
		found.put(type.getName(), type);
		for (Class<?> member : type.getDeclaredClasses()) {
			if (api(member.getModifiers()) && declared.contains(member.getSimpleName())) {
				add(found, member, declared);
			}
		}
	}

	private static Class<?> load(String name) {
		try {
			return Class.forName(name, false, JavaJdkMembersTest.class.getClassLoader());
		}
		catch (ClassNotFoundException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Class<?> element(Class<?> type) {
		Class<?> element = type;
		while (element.isArray()) {
			element = element.getComponentType();
		}
		return element;
	}

	/**
	 * A type as a class file's generic signature writes it (JVMS 4.7.9.1), made of what
	 * the JDK's reflection answers of it.
	 */
	static String signature(Type type) {
		return switch (type) {
			case Class<?> plain when plain.isPrimitive() -> String.valueOf(switch (plain.getName()) {
				case "boolean" -> 'Z';
				case "byte" -> 'B';
				case "char" -> 'C';
				case "short" -> 'S';
				case "int" -> 'I';
				case "long" -> 'J';
				case "float" -> 'F';
				case "double" -> 'D';
				default -> 'V';
			});
			case Class<?> plain when plain.isArray() -> "[" + signature(plain.getComponentType());
			case Class<?> plain -> "L" + plain.getName().replace('.', '/') + ";";
			case ParameterizedType parameterized -> parameterized(parameterized) + ";";
			case TypeVariable<?> variable -> "T" + variable.getName() + ";";
			case WildcardType wildcard -> wildcard.getLowerBounds().length > 0
					? "-" + signature(wildcard.getLowerBounds()[0]) : wildcard.getUpperBounds()[0] == Object.class ? "*"
							: "+" + signature(wildcard.getUpperBounds()[0]);
			case GenericArrayType array -> "[" + signature(array.getGenericComponentType());
			default -> throw new IllegalStateException(type.toString());
		};
	}

	private static String parameterized(ParameterizedType type) {
		Class<?> raw = (Class<?>) type.getRawType();
		StringBuilder written = new StringBuilder(type.getOwnerType() instanceof ParameterizedType owner
				? parameterized(owner) + "." + raw.getSimpleName() : "L" + raw.getName().replace('.', '/'));
		if (type.getActualTypeArguments().length > 0) {
			written.append('<');
			for (Type argument : type.getActualTypeArguments()) {
				written.append(signature(argument));
			}
			written.append('>');
		}
		return written.toString();
	}

	/** Type parameters as a signature declares them: each name and its bounds. */
	private static String parameters(TypeVariable<?>[] variables) {
		if (variables.length == 0) {
			return "";
		}
		StringBuilder written = new StringBuilder("<");
		for (TypeVariable<?> variable : variables) {
			written.append(variable.getName());
			for (Type bound : variable.getBounds()) {
				written.append(':').append(signature(bound));
			}
		}
		return written.append('>').toString();
	}

	/**
	 * A class's generic signature, where it declares type parameters or gives a type
	 * above it type arguments; else {@code null}.
	 */
	static @org.jspecify.annotations.Nullable String classSignature(Class<?> type) {
		boolean generic = type.getTypeParameters().length > 0
				|| type.getGenericSuperclass() instanceof ParameterizedType
				|| Arrays.stream(type.getGenericInterfaces()).anyMatch(each -> !(each instanceof Class));
		if (!generic) {
			return null;
		}
		StringBuilder written = new StringBuilder(parameters(type.getTypeParameters()));
		written.append(type.getGenericSuperclass() == null ? "-" : signature(type.getGenericSuperclass()));
		for (Type implemented : type.getGenericInterfaces()) {
			written.append(signature(implemented));
		}
		return written.toString();
	}

	/**
	 * A member's generic signature where its generic types are not the ones it erases to:
	 * a method's or a constructor's type parameters, parameters, return type and the
	 * types it throws, a field's type; else {@code null}.
	 */
	static @org.jspecify.annotations.Nullable String memberSignature(Object member) {
		if (member instanceof Field field) {
			return field.getGenericType() instanceof Class ? null : signature(field.getGenericType());
		}
		Executable executable = (Executable) member;
		Type returned = executable instanceof Method method ? method.getGenericReturnType() : void.class;
		Class<?> erased = executable instanceof Method method ? method.getReturnType() : void.class;
		if (executable.getTypeParameters().length == 0 && returned.equals(erased)
				&& Arrays.equals(executable.getGenericParameterTypes(), executable.getParameterTypes())
				&& Arrays.equals(executable.getGenericExceptionTypes(), executable.getExceptionTypes())) {
			return null;
		}
		StringBuilder written = new StringBuilder(parameters(executable.getTypeParameters())).append('(');
		for (Type parameter : executable.getGenericParameterTypes()) {
			written.append(signature(parameter));
		}
		written.append(')').append(signature(returned));
		for (Type thrown : executable.getGenericExceptionTypes()) {
			written.append('^').append(signature(thrown));
		}
		return written.toString();
	}

	/** Adds the classes a generic type names. */
	private static void named(@org.jspecify.annotations.Nullable Type type, List<Class<?>> mentioned) {
		switch (type) {
			case Class<?> plain -> mentioned.add(plain);
			case ParameterizedType parameterized -> {
				named(parameterized.getRawType(), mentioned);
				named(parameterized.getOwnerType(), mentioned);
				for (Type argument : parameterized.getActualTypeArguments()) {
					named(argument, mentioned);
				}
			}
			case WildcardType wildcard -> {
				for (Type bound : wildcard.getUpperBounds()) {
					named(bound, mentioned);
				}
				for (Type bound : wildcard.getLowerBounds()) {
					named(bound, mentioned);
				}
			}
			case GenericArrayType array -> named(array.getGenericComponentType(), mentioned);
			case null, default -> {
			}
		}
	}

	/**
	 * The lines of what a class or a member carries: its generic signature ({@code G} of
	 * a class, {@code g} of a member), its annotations as the JDK writes them
	 * ({@code A}), each parameter's ({@code p} and the parameter's place); the types of
	 * the annotations go among the types named.
	 */
	private static void carried(StringBuilder out, AnnotatedElement element, List<Class<?>> mentioned) {
		String signature = element instanceof Class<?> type ? classSignature(type) : memberSignature(element);
		if (signature != null) {
			out.append(element instanceof Class ? "G " : "g ").append(signature).append('\n');
			if (element instanceof Class<?> type) {
				named(type.getGenericSuperclass(), mentioned);
				for (Type each : type.getGenericInterfaces()) {
					named(each, mentioned);
				}
			}
			for (TypeVariable<?> variable : element instanceof GenericDeclaration declaration
					? declaration.getTypeParameters() : new TypeVariable<?>[0]) {
				for (Type bound : variable.getBounds()) {
					named(bound, mentioned);
				}
			}
			if (element instanceof Field field) {
				named(field.getGenericType(), mentioned);
			}
			if (element instanceof Executable executable) {
				for (Type each : executable.getGenericParameterTypes()) {
					named(each, mentioned);
				}
				if (executable instanceof Method method) {
					named(method.getGenericReturnType(), mentioned);
				}
			}
		}
		for (Annotation annotation : element.getDeclaredAnnotations()) {
			out.append("A ").append(annotation).append('\n');
			mentioned.add(annotation.annotationType());
		}
		if (element instanceof Executable executable) {
			Annotation[][] parameters = executable.getParameterAnnotations();
			for (int index = 0; index < parameters.length; index++) {
				for (Annotation annotation : parameters[index]) {
					out.append("p ").append(index).append(' ').append(annotation).append('\n');
					mentioned.add(annotation.annotationType());
				}
			}
		}
	}

	/**
	 * The table's text: the library's classes, the classes above them, the types named.
	 */
	static String text(TreeMap<String, Class<?>> library) {
		TreeMap<String, Class<?>> listed = new TreeMap<>(library);
		List<Class<?>> pending = new ArrayList<>(listed.values());
		while (!pending.isEmpty()) {
			Class<?> type = pending.remove(pending.size() - 1);
			List<Class<?>> above = new ArrayList<>(List.of(type.getInterfaces()));
			if (type.getSuperclass() != null) {
				above.add(type.getSuperclass());
			}
			for (Class<?> each : above) {
				if (listed.putIfAbsent(each.getName(), each) == null) {
					pending.add(each);
				}
			}
		}
		TreeMap<String, Class<?>> named = new TreeMap<>();
		StringBuilder out = new StringBuilder();
		for (Class<?> type : listed.values()) {
			out.append("C ")
				.append(type.getName())
				.append(' ')
				.append(type.getModifiers())
				.append(' ')
				.append(type.getModule().getName())
				.append(' ')
				.append(type.getSuperclass() == null ? "-" : type.getSuperclass().getName())
				.append(' ')
				.append(names(type.getInterfaces()))
				.append('\n');
			List<Class<?>> mentioned = new ArrayList<>();
			carried(out, type, mentioned);
			for (Method method : methods(type)) {
				out.append("m ")
					.append(method.getModifiers())
					.append(' ')
					.append(method.getName())
					.append(' ')
					.append(method.getReturnType().getName())
					.append(' ')
					.append(names(method.getParameterTypes()))
					.append(' ')
					.append(names(method.getExceptionTypes()))
					.append('\n');
				carried(out, method, mentioned);
				mentioned.add(method.getReturnType());
				mentioned.addAll(List.of(method.getParameterTypes()));
				mentioned.addAll(List.of(method.getExceptionTypes()));
			}
			for (Field field : fields(type)) {
				out.append("f ")
					.append(field.getModifiers())
					.append(' ')
					.append(field.getName())
					.append(' ')
					.append(field.getType().getName())
					.append('\n');
				carried(out, field, mentioned);
				mentioned.add(field.getType());
			}
			for (Constructor<?> constructor : constructors(type)) {
				out.append("k ")
					.append(constructor.getModifiers())
					.append(' ')
					.append(names(constructor.getParameterTypes()))
					.append(' ')
					.append(names(constructor.getExceptionTypes()))
					.append('\n');
				carried(out, constructor, mentioned);
				mentioned.addAll(List.of(constructor.getParameterTypes()));
				mentioned.addAll(List.of(constructor.getExceptionTypes()));
			}
			for (Class<?> each : mentioned) {
				Class<?> base = element(each);
				if (!base.isPrimitive() && !listed.containsKey(base.getName())) {
					named.put(base.getName(), base);
				}
			}
		}
		for (Class<?> type : named.values()) {
			out.append("N ")
				.append(type.getName())
				.append(' ')
				.append(type.getModifiers())
				.append(' ')
				.append(type.getModule().getName())
				.append('\n');
		}
		return "# The public and protected members of the JDK's classes the experimental Java subset's library\n"
				+ "# has, as a running JDK " + Runtime.version().feature()
				+ " reflects them, for its reflection over them: each class's\n"
				+ "# modifiers, module and the classes above it, its methods, fields and constructors sorted, each\n"
				+ "# with its generic signature and its annotations, then the types its members and annotations\n"
				+ "# name that are not listed. Generated by JavaJdkMembersTest from the JDK's public\n"
				+ "# java.lang.reflect; regenerate it there, never by hand (.kb/java-frontend.md, \"Reflection\n"
				+ "# over the JDK's classes\").\n" + out;
	}

	@Test
	void theShippedTableIsTheJdks() throws IOException {
		if (Runtime.version().feature() != 25) {
			abort("the table is JDK 25's; this JDK is " + Runtime.version());
		}
		TreeMap<String, Class<?>> library = libraryClasses();
		String generated = text(library);
		// the published algorithm of getMethods() and getFields() over the table gives
		// the JDK's answers, of every class of the library
		JavaJdkMembers table = JavaJdkMembers.parse(generated);
		for (Class<?> type : library.values()) {
			JavaJdkMembers.Type row = table.of(type.getName());
			assertThat(row).as(type.getName()).isNotNull();
			Set<String> jdk = new HashSet<>();
			for (Method method : type.getMethods()) {
				jdk.add(method.getDeclaringClass().getName() + "." + method.getName() + "("
						+ names(method.getParameterTypes()) + ")" + method.getReturnType().getName());
			}
			Set<String> ours = new HashSet<>();
			for (JavaJdkMembers.Member method : row.publicMethods) {
				ours.add(method.owner().name + "." + method.name() + "("
						+ (method.parameters().isEmpty() ? "-" : String.join(",", method.parameters())) + ")"
						+ method.type());
			}
			assertThat(ours).as("getMethods() of " + type.getName()).isEqualTo(jdk);
			List<String> jdkFields = new ArrayList<>();
			for (Field field : type.getFields()) {
				jdkFields.add(field.getDeclaringClass().getName() + "." + field.getName());
			}
			List<String> ourFields = new ArrayList<>();
			for (JavaJdkMembers.Member field : row.publicFields) {
				ourFields.add(field.owner().name + "." + field.name());
			}
			assertThat(ourFields).as("getFields() of " + type.getName()).containsExactlyInAnyOrderElementsOf(jdkFields);
		}
		String shipped = Files.readString(Path.of(SHIPPED), StandardCharsets.UTF_8);
		if (!generated.equals(shipped)) {
			Path out = Path.of("target", "jdk-members.txt");
			Files.createDirectories(out.getParent());
			Files.writeString(out, generated);
			assertThat(shipped)
				.as("copy " + out.toAbsolutePath() + " over " + SHIPPED
						+ " (the library's classes of the JDK changed, or the JDK did)")
				.isEqualTo(generated);
		}
	}

}
