package am.ik.maven;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * One node of a collected dependency graph: a dependency as it was reached on one path,
 * after the root's dependency management, with the nodes its POM contributes below it.
 *
 * <p>
 * Nothing is selected: every version a path reaches is a node of its own, the way Maven
 * Resolver's collector answers before its conflict resolver runs. A node whose artifact
 * (any version) already sits on its own path is a cycle: it is kept, marked, and not
 * expanded, since a nearer node with the same identity always wins over it.
 *
 * @param dependency the dependency, as managed
 * @param relocations the artifacts its POM relocated away from (empty when none)
 * @param premanagedVersion the version before dependency management changed it, or
 * {@code null} when management left it alone
 * @param premanagedScope the scope before dependency management changed it, or
 * {@code null} when management left it alone
 * @param cycle whether the node closes a cycle
 * @param children the nodes below it
 */
public record DependencyNode(Dependency dependency, List<Artifact> relocations, @Nullable String premanagedVersion,
		@Nullable String premanagedScope, boolean cycle, List<DependencyNode> children) {

	/**
	 * Copies the lists.
	 * @param dependency the dependency
	 * @param relocations the relocated-away-from artifacts
	 * @param premanagedVersion the version before management, or {@code null}
	 * @param premanagedScope the scope before management, or {@code null}
	 * @param cycle whether the node closes a cycle
	 * @param children the nodes below it
	 */
	public DependencyNode {
		Objects.requireNonNull(dependency, "dependency");
		relocations = List.copyOf(relocations);
		children = List.copyOf(children);
	}

	/**
	 * Returns the node's artifact.
	 * @return the dependency's artifact
	 */
	public Artifact artifact() {
		return this.dependency.artifact();
	}

}
