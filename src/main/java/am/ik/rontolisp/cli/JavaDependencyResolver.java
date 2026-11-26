package am.ik.rontolisp.cli;

import java.util.List;

import am.ik.maven.MavenResolver;
import am.ik.maven.RemoteRepository;

/**
 * Builds the resolver of {@code --java-dep} and {@code --java-source-dep} coordinates:
 * what a test replaces to resolve against fixture repositories.
 */
@FunctionalInterface
interface JavaDependencyResolver {

	/**
	 * @param repositories the repositories to search (Central, then the
	 * {@code --java-repository} ones)
	 * @param updateSnapshots whether {@code --java-update-snapshots} was given
	 * @return the resolver
	 */
	MavenResolver resolver(List<RemoteRepository> repositories, boolean updateSnapshots);

}
