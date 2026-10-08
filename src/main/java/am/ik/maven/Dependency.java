package am.ik.maven;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A dependency as a POM declares it, after the POM's own inheritance, interpolation and
 * dependency management: the artifact (its extension and classifier derived from the
 * declared type), the type, the scope, the optional flag and the exclusions.
 *
 * <p>
 * In a POM's {@code dependencies} the scope is never empty ({@code compile} when the POM
 * leaves it out); in its {@code dependencyManagement} an empty scope and a {@code null}
 * optional flag mean the entry does not manage them, and the version may be empty.
 *
 * @param artifact the artifact
 * @param type the declared type ({@code jar}, {@code test-jar}, {@code pom}, ...)
 * @param scope the scope, or the empty string when a managed entry does not set one
 * @param optional the optional flag, or {@code null} when not declared
 * @param exclusions the exclusions, in declaration order
 */
public record Dependency(Artifact artifact, String type, String scope, @Nullable Boolean optional,
		List<Exclusion> exclusions) {

	/**
	 * Copies the exclusions and validates that no part is {@code null}.
	 * @param artifact the artifact
	 * @param type the declared type
	 * @param scope the scope
	 * @param optional the optional flag, or {@code null}
	 * @param exclusions the exclusions
	 */
	public Dependency {
		Objects.requireNonNull(artifact, "artifact");
		Objects.requireNonNull(type, "type");
		Objects.requireNonNull(scope, "scope");
		exclusions = List.copyOf(exclusions);
	}

	/**
	 * A compile-scoped dependency on {@code artifact}, of the type its extension names,
	 * with no exclusions.
	 * @param artifact the artifact
	 * @return the dependency
	 */
	public static Dependency of(Artifact artifact) {
		return new Dependency(artifact, artifact.extension(), "compile", null, List.of());
	}

	/**
	 * Answers whether the dependency is declared optional.
	 * @return {@code true} for {@code <optional>true</optional>}
	 */
	public boolean isOptional() {
		return Boolean.TRUE.equals(this.optional);
	}

	/**
	 * Returns this dependency on another artifact.
	 * @param newArtifact the artifact
	 * @return the dependency
	 */
	public Dependency withArtifact(Artifact newArtifact) {
		return new Dependency(newArtifact, this.type, this.scope, this.optional, this.exclusions);
	}

}
