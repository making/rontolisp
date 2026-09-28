package am.ik.rontolisp.codegen.jvm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.jvm.ClassFileInfo;
import am.ik.jvm.JvmClassPath;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaImplementationType;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * The JVM compiler's {@link JavaClassLookup}: types described by class FILES, never
 * loaded -- a JDK's {@code ct.sym} for one Java release ({@code --java-release}, by
 * default the newest the JDK holds, i.e. its own) followed by the user's class path
 * ({@code --java-classpath}). It works where reflection over arbitrary classes does not
 * (the native CLI) and resolves against a release other than the one running.
 * <p>
 * It answers with {@link Class}'s semantics, re-implemented over the declared shapes:
 * {@link JavaType#methods} is {@code Class.getMethods()} (the {@code PublicMethods}
 * merge: a class method overrides an interface one, the most derived declaration of a
 * signature wins, covariant variants are all kept, a superinterface's static methods are
 * not inherited) followed by the interpreter's re-resolution of a method declared in an
 * inaccessible class to an accessible declaration ({@code Class.getMethod} semantics);
 * {@link JavaType#field} is {@code Class.getField}. {@code JvmClassFileLookupTest} holds
 * the two lookups to identical answers over a corpus.
 */
public final class JvmClassFileLookup implements JavaClassLookup, AutoCloseable {

	private static final Map<String, String> PRIMITIVE_DESCRIPTORS = Map.of("boolean", "Z", "byte", "B", "char", "C",
			"short", "S", "int", "I", "long", "J", "float", "F", "double", "D", "void", "V");

	private final JvmClassPath classPath;

	private final @Nullable Path ctSym;

	private final int release;

	private final ConcurrentHashMap<String, Optional<JavaType>> types = new ConcurrentHashMap<>();

	private final ConcurrentHashMap<JavaType, JavaImplementationType> implementations = new ConcurrentHashMap<>();

	private JvmClassFileLookup(JvmClassPath classPath, @Nullable Path ctSym, int release) {
		this.classPath = classPath;
		this.ctSym = ctSym;
		this.release = release;
	}

	/**
	 * A lookup over the JDK this process can find, for one release, plus a class path.
	 * The JDK is the running one ({@code java.home}), else {@code JAVA_HOME}, else the
	 * {@code java} on {@code PATH}; with none of them holding a {@code lib/ct.sym}, only
	 * the class path is searched ({@link #hasPlatform()} answers false).
	 * @param release the Java release, or {@code null} for the newest the JDK holds
	 * @param classPath directories and jar/zip archives searched after the platform
	 * @return the lookup
	 * @throws IllegalArgumentException when the JDK holds no such release
	 */
	public static JvmClassFileLookup forJdk(@Nullable Integer release, List<Path> classPath) {
		Path ctSym = findCtSym();
		int chosen;
		if (ctSym == null) {
			chosen = release != null ? release : Runtime.version().feature();
		}
		else {
			List<Integer> releases = JvmClassPath.releases(ctSym);
			if (releases.isEmpty()) {
				throw new IllegalArgumentException(ctSym + " holds no Java release");
			}
			if (release == null) {
				chosen = releases.get(releases.size() - 1);
			}
			else if (releases.contains(release)) {
				chosen = release;
			}
			else {
				throw new IllegalArgumentException("--java-release " + release + ": " + ctSym + " holds releases "
						+ releases.get(0) + " to " + releases.get(releases.size() - 1));
			}
		}
		return new JvmClassFileLookup(JvmClassPath.of(ctSym, chosen, classPath), ctSym, chosen);
	}

	/**
	 * A lookup over an explicit {@code ct.sym} and class path.
	 * @param ctSym a JDK's {@code lib/ct.sym}, or {@code null} for none
	 * @param release the Java release to read from it
	 * @param classPath directories and jar/zip archives searched after it
	 * @return the lookup
	 */
	public static JvmClassFileLookup of(@Nullable Path ctSym, int release, List<Path> classPath) {
		return new JvmClassFileLookup(JvmClassPath.of(ctSym, release, classPath), ctSym, release);
	}

	/**
	 * The {@code lib/ct.sym} of the JDK this process can find, or {@code null}.
	 * @return the file, or {@code null} when there is no JDK (a JRE has none)
	 */
	public static @Nullable Path findCtSym() {
		List<String> homes = new ArrayList<>();
		String javaHome = System.getProperty("java.home");
		if (javaHome != null) {
			homes.add(javaHome);
		}
		String envHome = System.getenv("JAVA_HOME");
		if (envHome != null) {
			homes.add(envHome);
		}
		return findCtSym(homes, System.getenv("PATH"));
	}

	/**
	 * The first {@code lib/ct.sym} of the given JDK homes, else of the JDK whose
	 * {@code bin/java} a {@code PATH} directory holds (through symbolic links).
	 * @param homes JDK home directories, in order
	 * @param path a {@code PATH} value, or {@code null}
	 * @return the file, or {@code null} when none holds one
	 */
	static @Nullable Path findCtSym(List<String> homes, @Nullable String path) {
		for (String home : homes) {
			Path ctSym = Path.of(home, "lib", "ct.sym");
			if (Files.isRegularFile(ctSym)) {
				return ctSym;
			}
		}
		if (path != null) {
			for (String dir : path.split(java.io.File.pathSeparator)) {
				if (dir.isEmpty()) {
					continue;
				}
				Path java = Path.of(dir, "java");
				if (!Files.isRegularFile(java)) {
					continue;
				}
				try {
					Path bin = java.toRealPath().getParent();
					Path home = bin == null ? null : bin.getParent();
					if (home != null && Files.isRegularFile(home.resolve("lib").resolve("ct.sym"))) {
						return home.resolve("lib").resolve("ct.sym");
					}
				}
				catch (IOException ex) {
					// Not a usable JDK: keep looking.
				}
			}
		}
		return null;
	}

	/**
	 * @return whether a JDK's platform classes are searched (a {@code ct.sym} was found)
	 */
	public boolean hasPlatform() {
		return this.ctSym != null;
	}

	/**
	 * @return the Java release the platform classes are read for
	 */
	public int release() {
		return this.release;
	}

	@Override
	public @Nullable JavaType find(String name) {
		// get + putIfAbsent, not computeIfAbsent: loading an array type finds its
		// component, a recursive update the map refuses.
		Optional<JavaType> cached = this.types.get(name);
		if (cached == null) {
			Optional<JavaType> loaded = Optional.ofNullable(load(name));
			cached = this.types.putIfAbsent(name, loaded);
			if (cached == null) {
				cached = loaded;
			}
		}
		return cached.orElse(null);
	}

	@Override
	public JavaImplementationType implementationOf(JavaType iface) {
		return this.implementations.computeIfAbsent(iface, JavaImplementationType::new);
	}

	private @Nullable JavaType load(String name) {
		if (PRIMITIVE_DESCRIPTORS.containsKey(name)) {
			return new Primitive(name);
		}
		if (name.startsWith("[")) {
			JavaType component = find(componentName(name.substring(1)));
			return component == null ? null : new Array(name, component);
		}
		JvmClassPath.Entry entry = this.classPath.find(name.replace('.', '/'));
		return entry == null ? null : new ClassType(entry);
	}

	/** The type name (getName spelling) of the rest of an array descriptor. */
	private static String componentName(String descriptor) {
		return switch (descriptor.charAt(0)) {
			case 'L' -> descriptor.substring(1, descriptor.length() - 1);
			case '[' -> descriptor;
			default -> primitiveName(descriptor.charAt(0));
		};
	}

	private static String primitiveName(char descriptor) {
		return switch (descriptor) {
			case 'Z' -> "boolean";
			case 'B' -> "byte";
			case 'C' -> "char";
			case 'S' -> "short";
			case 'I' -> "int";
			case 'J' -> "long";
			case 'F' -> "float";
			case 'D' -> "double";
			case 'V' -> "void";
			default -> throw new IllegalArgumentException("bad descriptor character " + descriptor);
		};
	}

	/** A field descriptor in getName spelling: {@code I} -> int, {@code [I} -> [I. */
	private static String typeName(String descriptor) {
		return switch (descriptor.charAt(0)) {
			case 'L' -> descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
			case '[' -> descriptor.replace('/', '.');
			default -> primitiveName(descriptor.charAt(0));
		};
	}

	/** The parameter type names, then the return type name, of a method descriptor. */
	private static List<String> signatureNames(String descriptor) {
		List<String> names = new ArrayList<>();
		int i = 1;
		while (descriptor.charAt(i) != ')') {
			int start = i;
			while (descriptor.charAt(i) == '[') {
				i++;
			}
			if (descriptor.charAt(i) == 'L') {
				i = descriptor.indexOf(';', i);
			}
			i++;
			names.add(typeName(descriptor.substring(start, i)));
		}
		names.add(typeName(descriptor.substring(i + 1)));
		return names;
	}

	/**
	 * A type that could not be found: it still has a name (so a signature naming it keeps
	 * its identity) but nothing is assignable to it but itself.
	 */
	private JavaType typeOrPlaceholder(String name) {
		JavaType type = find(name);
		if (type != null) {
			return type;
		}
		Optional<JavaType> missing = Optional.of(new Missing(name));
		Optional<JavaType> existing = this.types.putIfAbsent("?" + name, missing);
		return (existing != null ? existing : missing).orElseThrow();
	}

	@Override
	public void close() {
		this.classPath.close();
	}

	private @Nullable JavaType object() {
		return find("java.lang.Object");
	}

	private static final class Primitive implements JavaType {

		private final String name;

		Primitive(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public boolean isPrimitive() {
			return true;
		}

		@Override
		public boolean isArray() {
			return false;
		}

		@Override
		public @Nullable JavaType componentType() {
			return null;
		}

		@Override
		public boolean isInterface() {
			return false;
		}

		@Override
		public boolean isFinal() {
			return true;
		}

		@Override
		public boolean isPublic() {
			return true;
		}

		@Override
		public boolean isAbstract() {
			return false;
		}

		@Override
		public boolean isAssignableFrom(JavaType other) {
			return other == this;
		}

		@Override
		public List<JavaExecutable> methods(String name) {
			return List.of();
		}

		@Override
		public List<JavaExecutable> publicMethods() {
			return List.of();
		}

		@Override
		public List<JavaExecutable> constructors() {
			return List.of();
		}

		@Override
		public @Nullable JavaField field(String name) {
			return null;
		}

		@Override
		public boolean isAccessible() {
			return true;
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	private static final class Missing implements JavaType {

		private final String name;

		Missing(String name) {
			this.name = name;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public boolean isPrimitive() {
			return false;
		}

		@Override
		public boolean isArray() {
			return false;
		}

		@Override
		public @Nullable JavaType componentType() {
			return null;
		}

		@Override
		public boolean isInterface() {
			return false;
		}

		@Override
		public boolean isFinal() {
			return false;
		}

		@Override
		public boolean isPublic() {
			return false;
		}

		@Override
		public boolean isAbstract() {
			return false;
		}

		@Override
		public boolean isAssignableFrom(JavaType other) {
			return other == this;
		}

		@Override
		public List<JavaExecutable> methods(String name) {
			return List.of();
		}

		@Override
		public List<JavaExecutable> publicMethods() {
			return List.of();
		}

		@Override
		public List<JavaExecutable> constructors() {
			return List.of();
		}

		@Override
		public @Nullable JavaField field(String name) {
			return null;
		}

		@Override
		public boolean isAccessible() {
			return false;
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	private final class Array implements JavaType {

		private final String name;

		private final JavaType component;

		Array(String name, JavaType component) {
			this.name = name;
			this.component = component;
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public boolean isPrimitive() {
			return false;
		}

		@Override
		public boolean isArray() {
			return true;
		}

		@Override
		public JavaType componentType() {
			return this.component;
		}

		@Override
		public boolean isInterface() {
			return false;
		}

		@Override
		public boolean isFinal() {
			return true;
		}

		@Override
		public boolean isPublic() {
			return this.component.isPublic();
		}

		@Override
		public boolean isAbstract() {
			return false;
		}

		@Override
		public boolean isAssignableFrom(JavaType other) {
			if (!(other instanceof Array array)) {
				return false;
			}
			if (this.component.isPrimitive() || array.component.isPrimitive()) {
				return this.component == array.component;
			}
			return this.component.isAssignableFrom(array.component);
		}

		// An array class answers Object's public methods, as reflection does.
		@Override
		public List<? extends JavaExecutable> methods(String name) {
			JavaType object = object();
			return object == null ? List.of() : object.methods(name);
		}

		@Override
		public List<? extends JavaExecutable> publicMethods() {
			JavaType object = object();
			return object == null ? List.of() : object.publicMethods();
		}

		@Override
		public List<JavaExecutable> constructors() {
			return List.of();
		}

		@Override
		public @Nullable JavaField field(String name) {
			return null;
		}

		@Override
		public boolean isAccessible() {
			return this.component.isAccessible();
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	/** A (name, parameter type names) key of the getMethods merge. */
	private record Signature(String name, List<String> params) {
	}

	private final class ClassType implements JavaType {

		private final ClassFileInfo info;

		private final boolean accessible;

		private final String name;

		private volatile @Nullable Set<String> supertypes;

		private volatile @Nullable List<ClassMethod> publicMethods;

		private volatile @Nullable List<ClassMethod> declaredPublicMethods;

		private final ConcurrentHashMap<String, List<ClassMethod>> methodsByName = new ConcurrentHashMap<>();

		private volatile @Nullable List<ClassMethod> constructors;

		ClassType(JvmClassPath.Entry entry) {
			this.info = entry.info();
			this.accessible = entry.accessible();
			this.name = this.info.name().replace('/', '.');
		}

		@Override
		public String name() {
			return this.name;
		}

		@Override
		public boolean isPrimitive() {
			return false;
		}

		@Override
		public boolean isArray() {
			return false;
		}

		@Override
		public @Nullable JavaType componentType() {
			return null;
		}

		@Override
		public boolean isInterface() {
			return this.info.isInterface();
		}

		@Override
		public boolean isFinal() {
			return this.info.isFinal();
		}

		@Override
		public boolean isPublic() {
			return this.info.isPublic();
		}

		@Override
		public boolean isAbstract() {
			return (this.info.access() & (am.ik.jvm.AccessFlag.ACC_ABSTRACT | am.ik.jvm.AccessFlag.ACC_INTERFACE)) != 0;
		}

		@Override
		public boolean isAccessible() {
			return this.accessible;
		}

		/** The superclass reflection answers: none for an interface. */
		@Nullable ClassType superclass() {
			String superName = this.info.superName();
			if (superName == null || isInterface()) {
				return null;
			}
			return find(superName.replace('/', '.')) instanceof ClassType type ? type : null;
		}

		List<ClassType> interfaces() {
			List<ClassType> list = new ArrayList<>();
			for (String iface : this.info.interfaces()) {
				if (find(iface.replace('/', '.')) instanceof ClassType type) {
					list.add(type);
				}
			}
			return list;
		}

		@Override
		public boolean isAssignableFrom(JavaType other) {
			if (other == this) {
				return true;
			}
			if (other instanceof JavaImplementationType implementation) {
				return implementation.isAssignableTo(this);
			}
			if (other instanceof Array) {
				return "java.lang.Object".equals(this.name) || "java.lang.Cloneable".equals(this.name)
						|| "java.io.Serializable".equals(this.name);
			}
			if (other instanceof ClassType type) {
				return "java.lang.Object".equals(this.name) || type.supertypes().contains(this.name);
			}
			return false;
		}

		/** Every proper supertype's name, as far as the class path holds them. */
		Set<String> supertypes() {
			Set<String> cached = this.supertypes;
			if (cached == null) {
				Set<String> seen = new HashSet<>();
				Deque<String> pending = new ArrayDeque<>();
				String superName = this.info.superName();
				if (superName != null) {
					pending.add(superName);
				}
				pending.addAll(this.info.interfaces());
				while (!pending.isEmpty()) {
					String internal = pending.poll();
					if (!seen.add(internal.replace('/', '.'))) {
						continue;
					}
					if (find(internal.replace('/', '.')) instanceof ClassType type) {
						String next = type.info.superName();
						if (next != null) {
							pending.add(next);
						}
						pending.addAll(type.info.interfaces());
					}
				}
				cached = Set.copyOf(seen);
				this.supertypes = cached;
			}
			return cached;
		}

		List<ClassMethod> declaredPublicMethods() {
			List<ClassMethod> cached = this.declaredPublicMethods;
			if (cached == null) {
				List<ClassMethod> list = new ArrayList<>();
				for (ClassFileInfo.Member member : this.info.methods()) {
					if (member.isPublic() && !member.name().startsWith("<")) {
						list.add(new ClassMethod(this, member));
					}
				}
				cached = List.copyOf(list);
				this.declaredPublicMethods = cached;
			}
			return cached;
		}

		/** Class.getMethods(): the PublicMethods merge. */
		@Override
		public List<ClassMethod> publicMethods() {
			List<ClassMethod> cached = this.publicMethods;
			if (cached == null) {
				Map<Signature, List<ClassMethod>> merged = new LinkedHashMap<>();
				for (ClassMethod m : declaredPublicMethods()) {
					merge(merged, m);
				}
				ClassType superclass = superclass();
				if (superclass != null) {
					for (ClassMethod m : superclass.publicMethods()) {
						merge(merged, m);
					}
				}
				for (ClassType iface : interfaces()) {
					for (ClassMethod m : iface.publicMethods()) {
						// static interface methods are not inherited
						if (!m.isStatic()) {
							merge(merged, m);
						}
					}
				}
				List<ClassMethod> all = new ArrayList<>();
				for (List<ClassMethod> list : merged.values()) {
					all.addAll(list);
				}
				cached = List.copyOf(all);
				this.publicMethods = cached;
			}
			return cached;
		}

		@Override
		public List<ClassMethod> methods(String name) {
			List<ClassMethod> cached = this.methodsByName.get(name);
			if (cached == null) {
				List<ClassMethod> candidates = new ArrayList<>();
				for (ClassMethod method : publicMethods()) {
					if (method.name().equals(name)) {
						ClassMethod accessible = accessibleMethod(method);
						if (accessible != null) {
							candidates.add(accessible);
						}
					}
				}
				cached = List.copyOf(candidates);
				this.methodsByName.put(name, cached);
			}
			return cached;
		}

		@Override
		public List<ClassMethod> constructors() {
			List<ClassMethod> cached = this.constructors;
			if (cached == null) {
				List<ClassMethod> list = new ArrayList<>();
				for (ClassFileInfo.Member member : this.info.methods()) {
					if (member.isPublic() && "<init>".equals(member.name())) {
						list.add(new ClassMethod(this, member));
					}
				}
				cached = List.copyOf(list);
				this.constructors = cached;
			}
			return cached;
		}

		// Class.getField: declared, then the superinterfaces, then the superclass.
		@Override
		public @Nullable JavaField field(String fieldName) {
			for (ClassFileInfo.Member member : this.info.fields()) {
				if (member.isPublic() && member.name().equals(fieldName)) {
					return new ClassField(this, member);
				}
			}
			for (ClassType iface : interfaces()) {
				JavaField field = iface.field(fieldName);
				if (field != null) {
					return field;
				}
			}
			ClassType superclass = superclass();
			return superclass == null ? null : superclass.field(fieldName);
		}

		/** Class.getMethod(name, params): the most specific public declaration. */
		@Nullable ClassMethod getMethod(String methodName, List<String> params) {
			List<ClassMethod> found = methodsRecursive(methodName, params, true);
			if (found.isEmpty()) {
				return null;
			}
			ClassMethod m = found.get(0);
			for (int i = 1; i < found.size(); i++) {
				ClassMethod m2 = found.get(i);
				if (m2.returnType() != m.returnType() && m.returnType().isAssignableFrom(m2.returnType())) {
					m = m2;
				}
			}
			return m;
		}

		private List<ClassMethod> methodsRecursive(String methodName, List<String> params, boolean includeStatic) {
			List<ClassMethod> result = new ArrayList<>();
			for (ClassMethod m : declaredPublicMethods()) {
				if ((includeStatic || !m.isStatic()) && m.name().equals(methodName) && m.paramNames().equals(params)) {
					result.add(m);
				}
			}
			if (!result.isEmpty()) {
				return result;
			}
			ClassType superclass = superclass();
			if (superclass != null) {
				result = superclass.methodsRecursive(methodName, params, includeStatic);
			}
			Map<Signature, List<ClassMethod>> merged = new LinkedHashMap<>();
			for (ClassMethod m : result) {
				merge(merged, m);
			}
			for (ClassType iface : interfaces()) {
				for (ClassMethod m : iface.methodsRecursive(methodName, params, false)) {
					merge(merged, m);
				}
			}
			List<ClassMethod> all = new ArrayList<>();
			for (List<ClassMethod> list : merged.values()) {
				all.addAll(list);
			}
			return all;
		}

		@Override
		public String toString() {
			return this.name;
		}

	}

	// PublicMethods.MethodList.merge: among one (name, parameters) signature and one
	// return type, keep the most specific declaration; a class method knocks out an
	// interface one.
	private static void merge(Map<Signature, List<ClassMethod>> merged, ClassMethod method) {
		List<ClassMethod> list = merged.computeIfAbsent(new Signature(method.name(), method.paramNames()),
				k -> new ArrayList<>());
		ClassType dclass = method.owner;
		String rtype = method.returnName();
		for (int i = 0; i < list.size(); i++) {
			ClassMethod existing = list.get(i);
			if (!rtype.equals(existing.returnName())) {
				continue;
			}
			ClassType xdclass = existing.owner;
			if (dclass.isInterface() == xdclass.isInterface()) {
				if (dclass.isAssignableFrom(xdclass)) {
					return; // the existing method is the same or overrides the new one
				}
				if (xdclass.isAssignableFrom(dclass)) {
					list.remove(i); // the new method overrides the existing one
					i--;
				}
			}
			else if (dclass.isInterface()) {
				return; // a class method takes precedence over an interface method
			}
			else {
				list.remove(i);
				i--;
			}
		}
		list.add(method);
	}

	// The interpreter's re-resolution of a public method whose declaring class it cannot
	// call through (not public, or its package not exported): the same method on an
	// accessible superinterface or superclass (ReflectiveJavaClasses.accessibleMethod).
	private static @Nullable ClassMethod accessibleMethod(ClassMethod method) {
		if (method.owner.isAccessible()) {
			return method;
		}
		for (ClassType c = method.owner; c != null; c = c.superclass()) {
			ClassMethod onInterface = accessibleOnInterfaces(c, method);
			if (onInterface != null) {
				return onInterface;
			}
			if (c != method.owner) {
				ClassMethod declared = accessibleDeclaration(c, method);
				if (declared != null) {
					return declared;
				}
			}
		}
		return null;
	}

	private static @Nullable ClassMethod accessibleOnInterfaces(ClassType type, ClassMethod method) {
		for (ClassType iface : type.interfaces()) {
			ClassMethod declared = accessibleDeclaration(iface, method);
			if (declared != null) {
				return declared;
			}
			ClassMethod nested = accessibleOnInterfaces(iface, method);
			if (nested != null) {
				return nested;
			}
		}
		return null;
	}

	private static @Nullable ClassMethod accessibleDeclaration(ClassType type, ClassMethod method) {
		ClassMethod declared = type.getMethod(method.name(), method.paramNames());
		return declared != null && declared.owner.isAccessible() ? declared : null;
	}

	private final class ClassMethod implements JavaExecutable {

		private final ClassType owner;

		private final ClassFileInfo.Member member;

		private final List<String> signature;

		private volatile @Nullable List<JavaType> parameterTypes;

		private volatile @Nullable JavaType returnType;

		private final List<String> paramNames;

		ClassMethod(ClassType owner, ClassFileInfo.Member member) {
			this.owner = owner;
			this.member = member;
			this.signature = List.copyOf(signatureNames(member.descriptor()));
			this.paramNames = this.signature.subList(0, this.signature.size() - 1);
		}

		List<String> paramNames() {
			return this.paramNames;
		}

		String returnName() {
			return this.signature.get(this.signature.size() - 1);
		}

		@Override
		public JavaType declaringClass() {
			return this.owner;
		}

		@Override
		public String name() {
			return this.member.name();
		}

		@Override
		public List<JavaType> parameterTypes() {
			List<JavaType> cached = this.parameterTypes;
			if (cached == null) {
				List<JavaType> list = new ArrayList<>();
				for (String param : paramNames()) {
					list.add(typeOrPlaceholder(param));
				}
				cached = List.copyOf(list);
				this.parameterTypes = cached;
			}
			return cached;
		}

		@Override
		public JavaType returnType() {
			JavaType cached = this.returnType;
			if (cached == null) {
				cached = isConstructor() ? this.owner : typeOrPlaceholder(returnName());
				this.returnType = cached;
			}
			return cached;
		}

		@Override
		public boolean isVarArgs() {
			return (this.member.access() & am.ik.jvm.AccessFlag.ACC_VARARGS) != 0;
		}

		@Override
		public boolean isStatic() {
			return this.member.isStatic();
		}

		@Override
		public boolean isConstructor() {
			return "<init>".equals(this.member.name());
		}

		@Override
		public boolean isAbstract() {
			return (this.member.access() & am.ik.jvm.AccessFlag.ACC_ABSTRACT) != 0;
		}

		@Override
		public String toString() {
			return this.owner.name + "." + name() + paramNames();
		}

	}

	private final class ClassField implements JavaField {

		private final ClassType owner;

		private final ClassFileInfo.Member member;

		ClassField(ClassType owner, ClassFileInfo.Member member) {
			this.owner = owner;
			this.member = member;
		}

		@Override
		public JavaType declaringClass() {
			return this.owner;
		}

		@Override
		public String name() {
			return this.member.name();
		}

		@Override
		public JavaType type() {
			return typeOrPlaceholder(typeName(this.member.descriptor()));
		}

		@Override
		public boolean isStatic() {
			return this.member.isStatic();
		}

	}

}
