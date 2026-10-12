package am.ik.rontolisp.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import am.ik.rontolisp.clojure.ClojureJavaClasses;
import am.ik.rontolisp.compiler.JavaClassLookup;
import am.ik.rontolisp.compiler.JavaExecutable;
import am.ik.rontolisp.compiler.JavaField;
import am.ik.rontolisp.compiler.JavaType;
import org.jspecify.annotations.Nullable;

/**
 * The Clojure lowering's Java classes over a {@code java:} class lookup: what a native
 * image hands the lowering, the class files of the JDK it finds and of the program's
 * class path ({@link SourceLoader#javaClassLookup()}), where its own reflection holds
 * only what its build registered. The lookup answers with {@link Class}'s semantics, so
 * the lowering reads the classes as reflection on a JVM reads them; a primitive's name
 * names no class here, as {@code Class.forName} finds none. A name found is remembered,
 * an absent one asked again: the class path grows while a lowering resolves the program's
 * dependencies.
 */
final class ClojureClassLookup implements ClojureJavaClasses {

	private final JavaClassLookup lookup;

	private final ConcurrentHashMap<String, JavaClass> classes = new ConcurrentHashMap<>();

	ClojureClassLookup(JavaClassLookup lookup) {
		this.lookup = lookup;
	}

	@Override
	public @Nullable JavaClass find(String name) {
		JavaClass known = this.classes.get(name);
		if (known != null) {
			return known;
		}
		JavaType type = this.lookup.find(name);
		if (type == null || type.isPrimitive()) {
			return null;
		}
		JavaClass made = new Type(type);
		JavaClass raced = this.classes.putIfAbsent(name, made);
		return raced != null ? raced : made;
	}

	private static Member member(JavaExecutable executable) {
		List<String> parameters = new ArrayList<>();
		for (JavaType parameter : executable.parameterTypes()) {
			parameters.add(parameter.name());
		}
		return new Member(executable.isConstructor() ? "<init>" : executable.name(), executable.declaringClass().name(),
				List.copyOf(parameters), executable.returnType().name(), executable.isStatic(), executable.isVarArgs(),
				executable.isSynthetic(), executable.isBridge(), executable.isAbstract());
	}

	/** A type the lookup found. */
	private static final class Type implements JavaClass {

		private final JavaType type;

		private volatile @Nullable List<Member> methods;

		private volatile @Nullable List<Member> constructors;

		Type(JavaType type) {
			this.type = type;
		}

		@Override
		public String name() {
			return this.type.name();
		}

		@Override
		public boolean isInterface() {
			return this.type.isInterface();
		}

		@Override
		public boolean isPublic() {
			return this.type.isPublic();
		}

		@Override
		public boolean isAbstract() {
			return this.type.isAbstract();
		}

		@Override
		public boolean isFinal() {
			return this.type.isFinal();
		}

		@Override
		public @Nullable String superclass() {
			return this.type.superclassName();
		}

		@Override
		public List<String> interfaces() {
			return this.type.interfaceNames();
		}

		@Override
		public List<Member> methods() {
			List<Member> cached = this.methods;
			if (cached == null) {
				List<Member> list = new ArrayList<>();
				for (JavaExecutable method : this.type.publicMethods()) {
					list.add(member(method));
				}
				cached = List.copyOf(list);
				this.methods = cached;
			}
			return cached;
		}

		@Override
		public List<Member> constructors() {
			List<Member> cached = this.constructors;
			if (cached == null) {
				List<Member> list = new ArrayList<>();
				for (JavaExecutable constructor : this.type.constructors()) {
					list.add(member(constructor));
				}
				cached = List.copyOf(list);
				this.constructors = cached;
			}
			return cached;
		}

		@Override
		public boolean hasPublicFields() {
			return this.type.hasPublicFields();
		}

		@Override
		public @Nullable Field field(String name) {
			JavaField field = this.type.field(name);
			return field == null ? null : new Field(field.name(), field.type().name(), field.isStatic());
		}

		@Override
		public boolean isAssignableFrom(JavaClass other) {
			return other instanceof Type found && this.type.isAssignableFrom(found.type);
		}

		@Override
		public String toString() {
			return name();
		}

	}

}
