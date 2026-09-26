package am.ik.rontolisp.compiler;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * The class of the object a {@code java:reify} or {@code java:proxy} of an interface
 * {@code I} makes, as the {@code java:} resolution sees it: a class no program can name,
 * which extends {@code Object} and implements {@code I} and {@code java.io.Serializable}
 * -- the supertypes of a {@link java.lang.reflect.Proxy} class, the interpreter's, less
 * {@code Proxy} itself; a compiled program's generated class declares exactly these
 * ({@code codegen.jvm.JvmJavaImplementations}).
 * <p>
 * It is the KIND of such an object ({@link JavaKind}), so a call that passes one resolves
 * before it runs: its cost against a parameter type depends on {@code I} alone
 * ({@link #isAssignableTo}), never on which class made it. Canonical per interface within
 * a lookup ({@link JavaClassLookup#implementationOf}); both lookups' types answer
 * {@link JavaType#isAssignableFrom} for it through {@link #isAssignableTo}.
 */
public final class JavaImplementationType implements JavaType {

	private final JavaType iface;

	/**
	 * @param iface the interface the object implements
	 */
	public JavaImplementationType(JavaType iface) {
		this.iface = iface;
	}

	/**
	 * @return the interface the object implements
	 */
	public JavaType iface() {
		return this.iface;
	}

	/**
	 * Whether a value of this kind is assignable to {@code target}: {@code Object},
	 * {@code java.io.Serializable}, the interface and its superinterfaces.
	 * @param target a type
	 * @return whether {@code target.isAssignableFrom(this)}
	 */
	public boolean isAssignableTo(JavaType target) {
		return target == this || "java.lang.Object".equals(target.name())
				|| "java.io.Serializable".equals(target.name()) || target.isAssignableFrom(this.iface);
	}

	@Override
	public String name() {
		return "implementation of " + this.iface.name();
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
		return true;
	}

	// No program names the class, so it is never linkable.
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
	public List<? extends JavaExecutable> methods(String name) {
		return this.iface.methods(name);
	}

	@Override
	public List<? extends JavaExecutable> publicMethods() {
		return this.iface.publicMethods();
	}

	@Override
	public List<? extends JavaExecutable> constructors() {
		return List.of();
	}

	@Override
	public @Nullable JavaField field(String name) {
		return this.iface.field(name);
	}

	@Override
	public boolean isAccessible() {
		return this.iface.isAccessible();
	}

	@Override
	public String toString() {
		return name();
	}

}
