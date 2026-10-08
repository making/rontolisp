import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.collection.CollectResult;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactDescriptorRequest;
import org.eclipse.aether.resolution.ArtifactDescriptorResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.util.graph.manager.DependencyManagerUtils;

/**
 * Maven's own answer for the am.ik.maven fixture repository -- the program that wrote
 * every {@code *.txt} beside it, which MavenOracleParityTest replays against
 * MavenResolver. Not compiled by the build (it needs Maven's own jars); run it with a
 * Maven 3.9 distribution's {@code lib/} on the class path, from the repository root:
 *
 * <pre>
 * lib=$MAVEN_HOME/lib
 * javac -d /tmp/oracle -cp "$lib/*" src/test/resources/am/ik/maven/oracle/MavenOracle.java
 * java -cp "/tmp/oracle:$lib/*" MavenOracle src/test/resources/am/ik/maven/repo descriptor test.inherit:child:2
 * </pre>
 *
 * A case file is its first line (the arguments after the repository, behind {@code # })
 * followed by this program's output for them; to re-measure one, run that line and
 * replace the rest.
 *
 * <p>
 * Modes: {@code descriptor} (each artifact's effective dependencies), {@code collect}
 * (the collected graph, conflict resolution off: what MavenResolver.collect answers),
 * {@code resolve} (Maven's nearest-wins tree: MavenResolver.resolve) and
 * {@code classpath} (that tree's runtime class path as maven-core builds a project's:
 * RepositoryUtils.toArtifacts' preorder, each artifact once, kept when its type
 * constitutes a build path and its scope is compile or runtime -- what
 * DependencyGraph.runtimeClassPath answers). Arguments:
 * {@code -Dname=value} sets a system property; {@code COORDS[@scope][?][#g:a]...} is a
 * dependency ({@code ?} optional, each {@code #g:a} an exclusion), dependency management
 * after {@code --managed}. The session is Maven's defaults -- lenient descriptor policy,
 * so a missing or invalid POM is a warning -- with checksums ignored (the fixture carries
 * none) and POM-declared repositories ignored (as MavenResolver ignores them), over fixed
 * system properties: java.version 25.0.4, os Linux amd64 6.8.0, java.home
 * /nonexistent/jdk.
 */
public class MavenOracle {

	public static void main(String[] args) throws Exception {
		Path repo = Path.of(args[0]).toAbsolutePath();
		String mode = args[1];
		Map<String, String> sys = new LinkedHashMap<>();
		sys.put("java.version", "25.0.4");
		sys.put("java.home", "/nonexistent/jdk");
		sys.put("os.name", "Linux");
		sys.put("os.arch", "amd64");
		sys.put("os.version", "6.8.0");
		List<Dependency> deps = new ArrayList<>();
		List<Dependency> managed = new ArrayList<>();
		boolean inManaged = false;
		for (int i = 2; i < args.length; i++) {
			String a = args[i];
			if (a.startsWith("-D")) {
				String[] kv = a.substring(2).split("=", 2);
				sys.put(kv[0], kv.length > 1 ? kv[1] : "");
				continue;
			}
			if (a.equals("--managed")) {
				inManaged = true;
				continue;
			}
			String[] hashes = a.split("#");
			List<Exclusion> exclusions = new ArrayList<>();
			for (int h = 1; h < hashes.length; h++) {
				String[] ga = hashes[h].split(":");
				exclusions.add(new Exclusion(ga[0], ga[1], "*", "*"));
			}
			String head = hashes[0];
			Boolean optional = null;
			if (head.endsWith("?")) {
				optional = Boolean.TRUE;
				head = head.substring(0, head.length() - 1);
			}
			String[] cs = head.split("@");
			Dependency d = new Dependency(new DefaultArtifact(cs[0]), cs.length > 1 ? cs[1] : (inManaged ? "" : "compile"),
					optional, exclusions);
			(inManaged ? managed : deps).add(d);
		}
		DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
		locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
		locator.addService(TransporterFactory.class, FileTransporterFactory.class);
		RepositorySystem system = locator.getService(RepositorySystem.class);
		DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
		Path local = Files.createTempDirectory("oracle-local");
		localPrefix = local.toString();
		repoPrefix = repo.toUri().toString();
		session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(local.toFile())));
		session.setSystemProperties(sys);
		session.setConfigProperties(sys);
		session.setConfigProperty(DependencyManagerUtils.CONFIG_PROP_VERBOSE, true);
		session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_IGNORE);
		session.setIgnoreArtifactDescriptorRepositories(true);
		List<String> warnings = new ArrayList<>();
		// A requested dependency is typed by its extension, as maven-core types a
		// project's dependencies through the session's type registry.
		deps.replaceAll(d -> typed(d, session));
		managed.replaceAll(d -> typed(d, session));
		session.setRepositoryListener(new AbstractRepositoryListener() {

			@Override
			public void artifactDescriptorInvalid(RepositoryEvent event) {
				warnings.add("warning invalid " + event.getArtifact() + ": " + firstLine(event.getException()));
			}

			@Override
			public void artifactDescriptorMissing(RepositoryEvent event) {
				warnings.add("warning missing " + event.getArtifact());
			}

		});
		RemoteRepository remote = new RemoteRepository.Builder("fixture", "default", repo.toUri().toString()).build();
		List<RemoteRepository> repos = List.of(remote);
		switch (mode) {
			case "descriptor" -> {
				for (Dependency d : deps) {
					try {
						ArtifactDescriptorResult r = system.readArtifactDescriptor(session,
								new ArtifactDescriptorRequest(d.getArtifact(), repos, null));
						System.out.println("artifact " + r.getArtifact());
						for (Artifact rel : r.getRelocations()) {
							System.out.println("relocation " + rel);
						}
						for (Dependency x : r.getDependencies()) {
							System.out.println("dep " + fmt(x));
						}
						for (Dependency x : r.getManagedDependencies()) {
							System.out.println("managed " + fmt(x));
						}
					}
					catch (Exception ex) {
						System.out.println("error " + firstLine(ex));
					}
					warnings.forEach(System.out::println);
					warnings.clear();
				}
			}
			case "collect", "resolve", "classpath" -> {
				if (mode.equals("collect")) {
					session.setDependencyGraphTransformer(null);
				}
				CollectRequest req = new CollectRequest();
				req.setDependencies(deps);
				req.setManagedDependencies(managed);
				req.setRepositories(repos);
				try {
					CollectResult res = system.collectDependencies(session, req);
					if (mode.equals("classpath")) {
						java.util.Set<String> seen = new java.util.HashSet<>();
						classPath(res.getRoot().getChildren(), seen);
					}
					else {
						print(res.getRoot(), "", new ArrayList<>());
					}
				}
				catch (Exception ex) {
					System.out.println("error " + firstLine(ex));
				}
				warnings.forEach(System.out::println);
			}
			default -> throw new IllegalArgumentException(mode);
		}
	}

	static Dependency typed(Dependency d, DefaultRepositorySystemSession session) {
		Artifact a = d.getArtifact();
		org.eclipse.aether.artifact.ArtifactType type = session.getArtifactTypeRegistry().get(a.getExtension());
		return type == null ? d
				: d.setArtifact(new DefaultArtifact(a.getGroupId(), a.getArtifactId(), a.getClassifier(),
						a.getExtension(), a.getVersion(), type));
	}

	/** The temporary local repository and the fixture's absolute path, kept out of the output. */
	static String localPrefix = "";

	static String repoPrefix = "";

	static String firstLine(Throwable ex) {
		StringBuilder text = new StringBuilder(String.valueOf(ex.getMessage()));
		for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
			text.append(" <- ").append(cause.getMessage());
		}
		return text.toString()
			.replace('\n', ' ')
			.replace(localPrefix, "<local>")
			.replace(repoPrefix, "<repo>")
			.replaceAll(" +$", "");
	}

	static String fmt(Dependency d) {
		Artifact a = d.getArtifact();
		StringBuilder sb = new StringBuilder(a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getExtension() + ":"
				+ a.getClassifier() + ":" + a.getVersion());
		sb.append(" scope=").append(d.getScope()).append(" optional=").append(d.getOptional());
		if (!d.getExclusions().isEmpty()) {
			StringJoiner exclusions = new StringJoiner(",", " exclusions=", "");
			for (Exclusion e : d.getExclusions()) {
				exclusions.add(e.getGroupId() + ":" + e.getArtifactId());
			}
			sb.append(exclusions);
		}
		return sb.toString();
	}

	/**
	 * maven-core's RepositoryUtils.toArtifacts (a preorder into a LinkedHashSet of Maven
	 * artifacts, equal by groupId, artifactId, version, type and classifier), then
	 * MavenProject.getRuntimeClasspathElements' filter: the handler adds the artifact to a
	 * class path (RepositoryUtils.newHandler reads the resolver type's
	 * constitutesBuildPath) and the scope is compile or runtime.
	 */
	static void classPath(List<DependencyNode> nodes, java.util.Set<String> seen) {
		for (DependencyNode n : nodes) {
			Dependency d = n.getDependency();
			Artifact a = d.getArtifact();
			String type = a.getProperty("type", a.getExtension());
			String key = a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion() + ":" + type + ":"
					+ a.getClassifier();
			if (seen.add(key) && Boolean.parseBoolean(a.getProperty("constitutesBuildPath", ""))
					&& (d.getScope().equals("compile") || d.getScope().equals("runtime"))) {
				System.out.println("classpath " + a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getExtension()
						+ ":" + a.getClassifier() + ":" + a.getVersion());
			}
			classPath(n.getChildren(), seen);
		}
	}

	static void print(DependencyNode n, String indent, List<List<DependencyNode>> path) {
		if (n.getDependency() != null) {
			StringBuilder sb = new StringBuilder(indent + fmt(n.getDependency()));
			String version = DependencyManagerUtils.getPremanagedVersion(n);
			if (version != null) {
				sb.append(" premanaged-version=").append(version);
			}
			String scope = DependencyManagerUtils.getPremanagedScope(n);
			if (scope != null) {
				sb.append(" premanaged-scope=").append(scope);
			}
			if (!n.getRelocations().isEmpty()) {
				sb.append(" relocations=").append(n.getRelocations());
			}
			boolean cycle = path.stream().anyMatch(l -> l == n.getChildren());
			if (cycle) {
				sb.append(" cycle");
			}
			System.out.println(sb);
			if (cycle) {
				return;
			}
		}
		path.add(n.getChildren());
		for (DependencyNode c : n.getChildren()) {
			print(c, n.getDependency() == null ? indent : indent + "  ", path);
		}
		path.remove(path.size() - 1);
	}

}
