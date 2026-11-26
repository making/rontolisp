package am.ik.wit;

import org.jspecify.annotations.Nullable;

/**
 * A reference to an interface (or, in an {@code include}, a world), either local by bare
 * name ({@code types}) or fully qualified through a package
 * ({@code wasi:io/streams@0.2.0} — note the version prints after the interface name).
 *
 * @param pkg the qualifying package, or {@code null} for a local reference
 * @param name the interface (or world) name
 */
public record WitRef(@Nullable WitPackageName pkg, String name) {

	/**
	 * A local (unqualified) reference.
	 * @param name the interface or world name
	 * @return the reference
	 */
	public static WitRef local(String name) {
		return new WitRef(null, name);
	}

	@Override
	public String toString() {
		if (this.pkg == null) {
			return this.name;
		}
		String version = this.pkg.version();
		return this.pkg.namespace() + ":" + this.pkg.name() + "/" + this.name + (version != null ? "@" + version : "");
	}

}
