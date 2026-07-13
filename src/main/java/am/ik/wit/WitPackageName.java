package am.ik.wit;

import org.jspecify.annotations.Nullable;

/**
 * A WIT package name, e.g. {@code wasi:cli@0.3.0} or {@code root:component}.
 *
 * @param namespace the namespace before the colon, e.g. {@code wasi}
 * @param name the package name after the colon, e.g. {@code cli}
 * @param version the version after {@code @}, e.g. {@code 0.3.0}, or {@code null} when
 * unversioned
 */
public record WitPackageName(String namespace, String name, @Nullable String version) {

	@Override
	public String toString() {
		return this.namespace + ":" + this.name + (this.version != null ? "@" + this.version : "");
	}

}
