package am.ik.maven;

import java.util.List;
import java.util.Objects;

/**
 * What an artifact's POM says about it, after Maven's model building: the parent chain,
 * the active profiles, interpolation, the imported and inherited dependency management.
 * Path-independent -- the same artifact answers the same descriptor wherever it sits in a
 * graph.
 *
 * <p>
 * A POM no repository has, or one Maven's model builder would reject, answers no
 * dependencies and one warning, the way Maven's descriptor reader does by default.
 *
 * @param artifact the artifact, after following relocations
 * @param relocations the artifacts relocated away from, in order (empty when none)
 * @param dependencies the declared dependencies, in effective-model order
 * @param managedDependencies the effective dependency management
 * @param warnings the POMs that were missing or invalid
 */
public record ArtifactDescriptor(Artifact artifact, List<Artifact> relocations, List<Dependency> dependencies,
		List<Dependency> managedDependencies, List<String> warnings) {

	/**
	 * Copies the lists.
	 * @param artifact the artifact
	 * @param relocations the relocated-away-from artifacts
	 * @param dependencies the dependencies
	 * @param managedDependencies the dependency management
	 * @param warnings the warnings
	 */
	public ArtifactDescriptor {
		Objects.requireNonNull(artifact, "artifact");
		relocations = List.copyOf(relocations);
		dependencies = List.copyOf(dependencies);
		managedDependencies = List.copyOf(managedDependencies);
		warnings = List.copyOf(warnings);
	}

}
