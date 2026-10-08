package am.ik.maven;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The effective model of a POM, built as Maven 3.9's {@code DefaultModelBuilder} builds a
 * model without a project directory or plugin processing, at a validation level
 * ({@link ModelValidator}): minimal for a dependency's POM, strict for what tools.deps
 * builds:
 * <ol>
 * <li>each POM of the parent chain is read (strictly from level 2.0 on, leniently when
 * the strict reader refuses it) and its raw model validated, a fatal problem stopping the
 * build; its duplicate dependencies are collapsed (the last declaration wins, at the
 * first one's position) and its duplicate plugins merged, its profiles' activation
 * interpolated against its own properties and its active profiles injected; the chain
 * ends with the super POM (its build defaults);</li>
 * <li>the chain is merged from the top: group id, version and description are inherited
 * when absent (the model version never is), properties merge with the child winning,
 * dependencies and dependency management merge by management key with the child's entry
 * winning whole, and the parent's entries the child lacks follow the child's own; the
 * build merges as {@link BuildMerger#inherit} merges it;</li>
 * <li>{@code ${...}} is expanded over the merged model ({@link Interpolator}), its build
 * and plugin configurations included;</li>
 * <li>the plugin management is merged into the plugins it manages;</li>
 * <li>{@code import}-scoped {@code pom} entries of the dependency management are replaced
 * by the imported POMs' effective dependency management, the model's own entries first,
 * then each import's in order, the first entry of a key winning;</li>
 * <li>dependency management fills each dependency's absent version, scope and system
 * path, and its exclusions when it has none; the optional flag is never managed;</li>
 * <li>an absent scope becomes {@code compile}, and the model is validated: an error
 * collected anywhere rejects it.</li>
 * </ol>
 * A parent's version range resolves to the highest version the repositories list, as
 * Maven's model resolver resolves it (an open upper bound refused); an import's is looked
 * up as written, which no repository has. A missing parent or imported POM is a
 * {@link MavenResolutionException}; anything else Maven's builder rejects is an
 * {@link InvalidPomException}.
 */
final class ModelBuilder {

	/** Reads POM files. */
	interface PomSource {

		/**
		 * Reads a POM, its version resolved first when it is a {@code SNAPSHOT},
		 * {@code LATEST} or {@code RELEASE}.
		 * @param pom the POM's coordinates
		 * @return its bytes, or {@code null} when no repository has it
		 * @throws MavenResolutionException if a repository cannot be read
		 */
		byte @Nullable [] read(Artifact pom) throws MavenResolutionException;

		/**
		 * Resolves a version range.
		 * @param pom the POM's coordinates, the version the range
		 * @return the versions the range admits
		 * @throws MavenResolutionException if the range is invalid
		 */
		VersionRangeResult versionRange(Artifact pom) throws MavenResolutionException;

	}

	/** Marks a POM no repository has. */
	private static final Object MISSING = new Object();

	/**
	 * Maven 3.9.16's super POM ({@code org/apache/maven/model/pom-4.0.0.xml} in
	 * maven-model-builder), the last model of every chain. What the reader skips is left
	 * out.
	 */
	private static final String SUPER_POM_XML = """
			<project>
			  <modelVersion>4.0.0</modelVersion>
			  <repositories>
			    <repository>
			      <id>central</id>
			      <url>https://repo.maven.apache.org/maven2</url>
			      <layout>default</layout>
			    </repository>
			  </repositories>
			  <pluginRepositories>
			    <pluginRepository>
			      <id>central</id>
			      <url>https://repo.maven.apache.org/maven2</url>
			      <layout>default</layout>
			    </pluginRepository>
			  </pluginRepositories>
			  <build>
			    <directory>${project.basedir}/target</directory>
			    <outputDirectory>${project.build.directory}/classes</outputDirectory>
			    <finalName>${project.artifactId}-${project.version}</finalName>
			    <testOutputDirectory>${project.build.directory}/test-classes</testOutputDirectory>
			    <sourceDirectory>${project.basedir}/src/main/java</sourceDirectory>
			    <scriptSourceDirectory>${project.basedir}/src/main/scripts</scriptSourceDirectory>
			    <testSourceDirectory>${project.basedir}/src/test/java</testSourceDirectory>
			    <resources>
			      <resource>
			        <directory>${project.basedir}/src/main/resources</directory>
			      </resource>
			    </resources>
			    <testResources>
			      <testResource>
			        <directory>${project.basedir}/src/test/resources</directory>
			      </testResource>
			    </testResources>
			    <pluginManagement>
			      <plugins>
			        <plugin>
			          <artifactId>maven-antrun-plugin</artifactId>
			          <version>3.1.0</version>
			        </plugin>
			        <plugin>
			          <artifactId>maven-assembly-plugin</artifactId>
			          <version>3.7.1</version>
			        </plugin>
			        <plugin>
			          <artifactId>maven-dependency-plugin</artifactId>
			          <version>3.7.0</version>
			        </plugin>
			        <plugin>
			          <artifactId>maven-release-plugin</artifactId>
			          <version>3.0.1</version>
			        </plugin>
			      </plugins>
			    </pluginManagement>
			  </build>
			  <reporting/>
			  <profiles>
			    <profile>
			      <id>release-profile</id>
			      <activation>
			        <property>
			          <name>performRelease</name>
			          <value>true</value>
			        </property>
			      </activation>
			      <build>
			        <plugins>
			          <plugin>
			            <inherited>true</inherited>
			            <artifactId>maven-source-plugin</artifactId>
			            <executions>
			              <execution>
			                <id>attach-sources</id>
			                <goals>
			                  <goal>jar-no-fork</goal>
			                </goals>
			              </execution>
			            </executions>
			          </plugin>
			          <plugin>
			            <inherited>true</inherited>
			            <artifactId>maven-javadoc-plugin</artifactId>
			            <executions>
			              <execution>
			                <id>attach-javadocs</id>
			                <goals>
			                  <goal>jar</goal>
			                </goals>
			              </execution>
			            </executions>
			          </plugin>
			          <plugin>
			            <inherited>true</inherited>
			            <artifactId>maven-deploy-plugin</artifactId>
			          </plugin>
			        </plugins>
			      </build>
			    </profile>
			  </profiles>
			</project>
			""";

	private static final PomModel SUPER_POM = superPom();

	/** The raw child versions that name the parent's version. */
	private static final Set<String> PARENT_VERSION_REFERENCES = Set.of("${pom.version}", "${project.version}",
			"${pom.parent.version}", "${project.parent.version}");

	private final PomSource source;

	private final Map<String, String> system;

	/**
	 * Repository POMs as each reader read them, by POM: a {@link PomReader.Read}, the
	 * reader's refusal (a {@code String}), or MISSING. The lenient reader's, then the
	 * strict reader's (a parent read for a stricter build).
	 */
	private final Map<Artifact, Object> lenient = new HashMap<>();

	private final Map<Artifact, Object> strict = new HashMap<>();

	/**
	 * Effective models by POM (minimal level): a {@link PomModel}, an
	 * {@link InvalidPomException}, or MISSING.
	 */
	private final Map<Artifact, Object> effective = new HashMap<>();

	/**
	 * The dependency management an import contributes, and the problems its build
	 * reported, by imported id and the packaging its profiles were activated with.
	 * Maven's session cache keys it by id alone, which makes a BOM whose profiles test
	 * {@code packaging} answer whatever its first importer's packaging chose; with the
	 * packaging in the key a descriptor is the same whatever was resolved before it (what
	 * Maven answers without a session cache, which also reports the import's warnings to
	 * every importer).
	 */
	private final Map<String, Imported> imports = new HashMap<>();

	private record Imported(List<PomModel.Dep> managed, List<ModelProblem> problems) {
	}

	/**
	 * Creates a builder.
	 * @param source where POMs come from
	 * @param systemProperties the system properties profiles are activated by and
	 * expressions fall back to
	 */
	ModelBuilder(PomSource source, Map<String, String> systemProperties) {
		this.source = source;
		this.system = Map.copyOf(systemProperties);
	}

	/**
	 * Returns the effective model of a repository POM, built at the minimal validation
	 * level as Maven's descriptor reader builds it.
	 * @param pom the POM's coordinates
	 * @return the model, or {@code null} when no repository has the POM
	 * @throws InvalidPomException if Maven's model builder would reject the POM
	 * @throws MavenResolutionException if a parent or import cannot be resolved
	 */
	@Nullable PomModel effective(Artifact pom) throws InvalidPomException, MavenResolutionException {
		Object cached = this.effective.get(pom);
		if (cached == null) {
			try {
				ModelProblems problems = new ModelProblems();
				PomModel input = readRepository(pom, ModelValidator.MINIMAL, problems);
				if (input == null) {
					cached = MISSING;
				}
				else {
					// Maven's 3.9 profile activation context carries the input POM's
					// packaging as a user property, for its parents and imports too.
					cached = build(input, null, ModelValidator.MINIMAL, Map.of("packaging", input.effectivePackaging()),
							new LinkedHashSet<>(), problems);
				}
			}
			catch (InvalidPomException ex) {
				cached = ex;
			}
			this.effective.put(pom, cached);
		}
		if (cached == MISSING) {
			return null;
		}
		if (cached instanceof InvalidPomException invalid) {
			throw new InvalidPomException(invalid.problems());
		}
		return (PomModel) cached;
	}

	/**
	 * Returns the effective model of a POM given as bytes rather than coordinates -- one
	 * shipped inside a jar, Maven's {@code UrlModelSource} -- its parents and imports
	 * read from the source. What only the lenient reader reads is a warning.
	 * @param pom the POM's bytes
	 * @param level the validation level
	 * @return the model
	 * @throws InvalidPomException if Maven's model builder would reject the POM
	 * @throws MavenResolutionException if a parent or import cannot be resolved
	 */
	PomModel effective(byte[] pom, int level) throws InvalidPomException, MavenResolutionException {
		ModelProblems problems = new ModelProblems();
		PomModel input = readModel(pom, level, null, false, problems);
		return build(input, null, level, Map.of("packaging", input.effectivePackaging()), new LinkedHashSet<>(),
				problems);
	}

	/**
	 * Returns the effective model of a POM file, built as Maven builds one from a
	 * {@code FileModelSource} without a project directory (what tools.deps asks for): a
	 * parent is looked for at its {@code relativePath} beside the file first -- and so on
	 * up a chain read from files -- then in the repositories. The request names no POM
	 * file, so what only the lenient reader reads of this one is a warning; of a parent
	 * read beside it, an error.
	 * @param pom the POM file
	 * @param level the validation level
	 * @return the model
	 * @throws InvalidPomException if Maven's model builder would reject the POM
	 * @throws MavenResolutionException if the file cannot be read, or a parent or import
	 * cannot be resolved
	 */
	PomModel effective(Path pom, int level) throws InvalidPomException, MavenResolutionException {
		Path file = pom.toAbsolutePath().normalize();
		ModelProblems problems = new ModelProblems();
		PomModel input = readModel(readFile(file), level, file.toString(), false, problems);
		return build(input, file, level, Map.of("packaging", input.effectivePackaging()), new LinkedHashSet<>(),
				problems);
	}

	private static byte[] readFile(Path file) throws MavenResolutionException {
		try {
			return Files.readAllBytes(file);
		}
		catch (IOException ex) {
			throw new MavenResolutionException("Non-readable POM " + file + ": " + ex.getMessage(), ex);
		}
	}

	private static PomModel superPom() {
		try {
			return PomReader.read(SUPER_POM_XML.getBytes(StandardCharsets.UTF_8));
		}
		catch (InvalidPomException ex) {
			throw new IllegalStateException("the super POM does not read", ex);
		}
	}

	/**
	 * Maven's {@code readModel}: the strict reader from level 2.0 on, and the lenient one
	 * when it refuses; the raw model validated; a fatal problem stops the build.
	 * @param bytes the POM
	 * @param level the validation level
	 * @param location where it was read, for the messages
	 * @param pomFile whether the request names it as a POM file (a parent read beside its
	 * child): what only the lenient reader reads is then an error
	 */
	private static PomModel readModel(byte[] bytes, int level, @Nullable String location, boolean pomFile,
			ModelProblems problems) throws InvalidPomException {
		String at = location == null ? "" : " " + location;
		PomReader.Read read;
		try {
			read = PomReader.read(bytes, level >= ModelValidator.MAVEN_2_0);
		}
		catch (XmlParser.Malformed ex) {
			problems.fatal("Non-parseable POM" + at + ": " + ex.getMessage());
			throw problems.invalid();
		}
		if (read.malformed() != null) {
			problems.add(pomFile ? ModelProblem.Severity.ERROR : ModelProblem.Severity.WARNING,
					"Malformed POM" + at + ": " + read.malformed());
		}
		validateRaw(read.model(), level, problems);
		return read.model();
	}

	/**
	 * A repository POM through {@link #readModel}, each reader's result cached.
	 * @return the raw model, or {@code null} when no repository has it
	 */
	private @Nullable PomModel readRepository(Artifact pom, int level, ModelProblems problems)
			throws InvalidPomException, MavenResolutionException {
		boolean strictReader = level >= ModelValidator.MAVEN_2_0;
		Map<Artifact, Object> cache = strictReader ? this.strict : this.lenient;
		Object cached = cache.get(pom);
		if (cached == null) {
			byte[] bytes = this.source.read(pom);
			if (bytes == null) {
				cached = MISSING;
			}
			else {
				try {
					cached = PomReader.read(bytes, strictReader);
				}
				catch (XmlParser.Malformed ex) {
					cached = ex.getMessage();
				}
			}
			cache.put(pom, cached);
		}
		if (cached == MISSING) {
			return null;
		}
		if (cached instanceof String refusal) {
			problems.fatal("Non-parseable POM: " + refusal);
			throw problems.invalid();
		}
		if (!(cached instanceof PomReader.Read read)) {
			throw new IllegalStateException("no read of " + pom);
		}
		String malformed = read.malformed();
		if (malformed != null) {
			problems.warning("Malformed POM: " + malformed);
		}
		validateRaw(read.model(), level, problems);
		return read.model();
	}

	/** The raw model's validation; a fatal problem, its own or an earlier one, stops. */
	private static void validateRaw(PomModel model, int level, ModelProblems problems) throws InvalidPomException {
		try {
			ModelValidator.validateRaw(model, level, problems);
		}
		catch (NumberFormatException ex) {
			// Maven's model version comparison throws it out of the builder
			problems.fatal(ex.toString());
		}
		if (problems.hasFatal()) {
			throw problems.invalid();
		}
	}

	/**
	 * Builds an effective model.
	 * @param input the raw model, read and validated
	 * @param location the file it was read from, {@code null} for a repository's or one
	 * given as bytes (no parent is looked for beside it)
	 * @param level the validation level
	 * @param user the user properties
	 * @param importChain the imports being built, for their cycle check
	 * @param problems the problems so far
	 */
	private PomModel build(PomModel input, @Nullable Path location, int level, Map<String, String> user,
			Set<String> importChain, ModelProblems problems) throws InvalidPomException, MavenResolutionException {
		List<PomModel> lineage = new ArrayList<>();
		Set<String> parentIds = new LinkedHashSet<>();
		PomModel current = input;
		Path currentLocation = location;
		while (true) {
			lineage.add(activateProfiles(mergeDuplicates(current), user, problems));
			PomModel.Parent parent = current.parent();
			if (parent == null) {
				break;
			}
			String parentGroup = nonNull(parent.groupId());
			String parentArtifact = nonNull(parent.artifactId());
			String writtenVersion = nonNull(parent.version());
			// beside the child's file first, then the repositories
			LocalParent local = currentLocation == null ? null
					: localParent(current, currentLocation, current == input, level, problems);
			String parentVersion;
			if (local != null) {
				parentVersion = local.version();
			}
			else {
				String writtenChildId = (current.groupId() == null ? parentGroup : current.groupId()) + ":"
						+ current.artifactId() + ":" + (current.version() == null ? writtenVersion : current.version());
				parentVersion = parentVersion(parentGroup, parentArtifact, writtenVersion, writtenChildId);
			}
			String parentId = (local != null ? local.groupId() : parentGroup) + ":" + parentArtifact + ":"
					+ parentVersion;
			String childId = (current.groupId() == null ? parentGroup : current.groupId()) + ":" + current.artifactId()
					+ ":" + (current.version() == null ? parentVersion : current.version());
			PomModel parentModel;
			if (local != null) {
				parentModel = local.model();
			}
			else {
				// a parent from a repository is read at level 2.0 at most
				parentModel = readRepository(new Artifact(parentGroup, parentArtifact, parentVersion, "", "pom"),
						Math.min(level, ModelValidator.MAVEN_2_0), problems);
				if (parentModel == null) {
					throw new MavenResolutionException(
							"Non-resolvable parent POM " + parentId + " for " + childId + ": no repository has it");
				}
				// a version a range admitted: the child cannot inherit what it resolved
				// to (fatal at the next POM read, else once the model is built)
				if (!parentVersion.equals(writtenVersion)
						&& (current.version() == null || PARENT_VERSION_REFERENCES.contains(current.version()))) {
					problems.fatal("Version must be a constant");
				}
			}
			if (!"pom".equals(parentModel.effectivePackaging())) {
				problems.error("Invalid packaging for parent POM " + parentId + ", must be \"pom\" but is \""
						+ parentModel.effectivePackaging() + "\"");
			}
			if (lineage.size() == 1) {
				// Maven records the child, its group and version inherited, then each
				// grandparent onwards; the first parent itself is never recorded.
				parentIds.add(childId);
			}
			else if (!parentIds.add(parentId)) {
				problems.fatal("The parents form a cycle: " + String.join(" -> ", parentIds) + " -> " + parentId);
				throw problems.invalid();
			}
			current = parentModel;
			currentLocation = local == null ? null : local.location();
		}
		lineage.add(activateProfiles(SUPER_POM, user, problems));
		if (level >= ModelValidator.MAVEN_2_0) {
			checkPluginVersions(lineage, problems);
		}
		PomModel model = lineage.get(lineage.size() - 1);
		for (int i = lineage.size() - 2; i >= 0; i--) {
			model = inherit(lineage.get(i), model);
		}
		model = interpolate(model, user, problems);
		model = model.withBuild(BuildMerger.injectManagement(model.build()));
		model = importManagement(model, user, importChain, problems);
		model = injectManagement(model);
		model = injectDefaultScope(model);
		ModelValidator.validateEffective(model, level, this.system, problems);
		if (problems.hasErrors()) {
			throw problems.invalid();
		}
		return model;
	}

	/**
	 * {@code DefaultModelBuilder.checkPluginVersions} (level 2.0 on): a plugin no POM of
	 * the lineage gives a version, nor manages one, is warned about, in the order of
	 * Maven's {@code HashMap}.
	 */
	private static void checkPluginVersions(List<PomModel> lineage, ModelProblems problems) {
		Map<String, @Nullable String> versions = new HashMap<>();
		Map<String, @Nullable String> managedVersions = new HashMap<>();
		for (int i = lineage.size() - 1; i >= 0; i--) {
			PomModel.Build build = lineage.get(i).build();
			if (build == null) {
				continue;
			}
			for (PomModel.Plugin plugin : build.plugins()) {
				if (versions.get(plugin.key()) == null) {
					versions.put(plugin.key(), plugin.version());
				}
			}
			List<PomModel.Plugin> management = build.pluginManagement();
			if (management != null) {
				for (PomModel.Plugin plugin : management) {
					if (managedVersions.get(plugin.key()) == null) {
						managedVersions.put(plugin.key(), plugin.version());
					}
				}
			}
		}
		for (String key : versions.keySet()) {
			if (versions.get(key) == null && managedVersions.get(key) == null) {
				problems.warning("'build.plugins.plugin.version' for " + key + " is missing.");
			}
		}
	}

	/**
	 * A parent's version as Maven's model resolver resolves it: a range to the highest
	 * version the repositories list, which it must bound from above; any other version
	 * itself.
	 */
	private String parentVersion(String groupId, String artifactId, String version, String childId)
			throws MavenResolutionException {
		String parentId = groupId + ":" + artifactId + ":" + version;
		VersionRangeResult range;
		try {
			range = this.source.versionRange(new Artifact(groupId, artifactId, version, "", "pom"));
		}
		catch (MavenResolutionException ex) {
			throw new MavenResolutionException(
					"Non-resolvable parent POM " + parentId + " for " + childId + ": " + ex.getMessage(), ex);
		}
		String highest = range.highest();
		if (highest == null) {
			throw new MavenResolutionException("Non-resolvable parent POM " + parentId + " for " + childId
					+ ": No versions matched the requested parent version range '" + version + "'"
					+ (range.problems().isEmpty() ? "" : " (" + String.join("; ", range.problems()) + ")"));
		}
		if (range.constraint().isRange() && range.constraint().upperBound() == null) {
			throw new MavenResolutionException("Non-resolvable parent POM " + parentId + " for " + childId
					+ ": The requested parent version range '" + version + "' does not specify an upper bound");
		}
		return highest;
	}

	/**
	 * A parent read beside its child's file.
	 *
	 * @param model its raw model
	 * @param location its file
	 * @param groupId its group id, its own or its parent's
	 * @param version its version, its own or its parent's
	 */
	private record LocalParent(PomModel model, Path location, String groupId, String version) {
	}

	/**
	 * Maven's {@code readParentLocally} with a {@code FileModelSource}: the POM at the
	 * parent's {@code relativePath} beside the child's file ({@code pom.xml} inside a
	 * directory), read and validated as a POM file of the request whatever it names, then
	 * taken when it names the parent's group and artifact and its version is the parent's
	 * or one of the parent's range admits; else {@code null} (the repositories are
	 * asked).
	 * @param root whether the child is the model being built (a warning names the others)
	 */
	private @Nullable LocalParent localParent(PomModel child, Path childLocation, boolean root, int level,
			ModelProblems problems) throws InvalidPomException {
		PomModel.Parent parent = child.parent();
		String relativePath = parent == null ? "" : parent.effectiveRelativePath();
		Path directory = childLocation.getParent();
		if (parent == null || relativePath.isEmpty() || directory == null) {
			return null;
		}
		File related = new File(directory.toFile(),
				relativePath.replace('\\', File.separatorChar).replace('/', File.separatorChar));
		if (related.isDirectory()) {
			related = new File(related, "pom.xml");
		}
		if (!related.isFile() || !related.canRead()) {
			return null;
		}
		Path location = related.toPath().toAbsolutePath().normalize();
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(location);
		}
		catch (IOException ex) {
			problems.fatal("Non-readable POM " + location + ": " + ex.getMessage());
			throw problems.invalid();
		}
		PomModel candidate = readModel(bytes, level, location.toString(), true, problems);
		PomModel.Parent grandparent = candidate.parent();
		String groupId = candidate.groupId() != null ? candidate.groupId()
				: grandparent == null ? null : grandparent.groupId();
		String version = candidate.version() != null ? candidate.version()
				: grandparent == null ? null : grandparent.version();
		if (groupId == null || !groupId.equals(parent.groupId()) || candidate.artifactId() == null
				|| !candidate.artifactId().equals(parent.artifactId())) {
			problems.warning("'parent.relativePath'" + (root ? "" : " of POM " + sourceHint(child, childLocation))
					+ " points at " + groupId + ":" + candidate.artifactId() + " instead of " + parent.groupId() + ":"
					+ parent.artifactId() + ", please verify your project structure");
			return null;
		}
		if (version != null && parent.version() != null && !version.equals(parent.version())) {
			VersionConstraint range;
			try {
				range = VersionConstraint.parse(parent.version());
			}
			catch (MavenResolutionException ex) {
				return null;
			}
			if (!range.isRange() || !range.contains(GenericVersion.parse(version))) {
				return null; // version skew: the repositories are asked
			}
			if (child.version() == null || PARENT_VERSION_REFERENCES.contains(child.version())) {
				problems.fatal("Version must be a constant");
			}
		}
		return new LocalParent(candidate, location, groupId, nonNull(version));
	}

	/** {@code ModelProblemUtils.toSourceHint} of a model read from a file. */
	private static String sourceHint(PomModel model, Path location) {
		PomModel.Parent parent = model.parent();
		String groupId = model.groupId() != null ? model.groupId() : parent == null ? null : parent.groupId();
		String version = model.version() != null ? model.version() : parent == null ? null : parent.version();
		return (groupId == null ? "[unknown-group-id]" : groupId) + ":"
				+ (model.artifactId() == null ? "[unknown-artifact-id]" : model.artifactId()) + ":"
				+ (version == null ? "[unknown-version]" : version) + " (" + location + ")";
	}

	private static String nonNull(@Nullable String value) {
		return value == null ? "" : value;
	}

	private static boolean isEmpty(@Nullable String value) {
		return value == null || value.isEmpty();
	}

	/**
	 * A dependency declared twice keeps its first position and its last declaration; a
	 * plugin declared twice is merged ({@link BuildMerger#mergeDuplicates}).
	 */
	private static PomModel mergeDuplicates(PomModel model) {
		PomModel result = model.withBuild(BuildMerger.mergeDuplicates(model.build()));
		Map<String, PomModel.Dep> byKey = new LinkedHashMap<>();
		for (PomModel.Dep dep : model.dependencies()) {
			byKey.put(dep.managementKey(), dep);
		}
		if (byKey.size() == model.dependencies().size()) {
			return result;
		}
		return result.withDependencies(new ArrayList<>(byKey.values()), model.managedDependencies());
	}

	private PomModel activateProfiles(PomModel model, Map<String, String> user, ModelProblems problems) {
		if (model.profiles().isEmpty()) {
			return model;
		}
		// The activation is interpolated against the POM's own properties, not the
		// inherited ones, then the user and system properties.
		Interpolator activation = new Interpolator(
				List.of(Interpolator.of(model.properties()), Interpolator.of(user), Interpolator.of(this.system)));
		List<String> errors = new ArrayList<>();
		List<PomModel.Profile> interpolated = new ArrayList<>();
		for (PomModel.Profile profile : model.profiles()) {
			interpolated.add(profile.withActivation(interpolateActivation(profile.activation(), activation, errors)));
		}
		List<PomModel.Profile> active = ProfileActivator.active(interpolated, user, this.system, errors);
		for (String error : errors) {
			problems.error(error);
		}
		PomModel result = model;
		for (PomModel.Profile profile : active) {
			result = inject(result, profile);
		}
		return result;
	}

	private static PomModel.@Nullable Activation interpolateActivation(PomModel.@Nullable Activation activation,
			Interpolator interpolator, List<String> problems) {
		if (activation == null) {
			return null;
		}
		ActivationValues values = new ActivationValues(interpolator, problems);
		PomModel.Os os = activation.os();
		PomModel.Property property = activation.property();
		PomModel.FileCheck file = activation.file();
		return new PomModel.Activation(activation.activeByDefault(), values.of(activation.jdk()),
				os == null ? null
						: new PomModel.Os(values.of(os.name()), values.of(os.family()), values.of(os.arch()),
								values.of(os.version())),
				property == null ? null
						: new PomModel.Property(values.of(property.name()), values.of(property.value())),
				file == null ? null : new PomModel.FileCheck(values.path(file.exists()), values.path(file.missing())));
	}

	/** Interpolates activation values, collecting failures as problems. */
	private record ActivationValues(Interpolator interpolator, List<String> problems) {

		@Nullable String of(@Nullable String value) {
			try {
				return this.interpolator.interpolateNullable(value);
			}
			catch (Interpolator.CycleException ex) {
				this.problems.add("Failed to interpolate profile activation value " + value + ": " + ex.getMessage());
				return value;
			}
		}

		/**
		 * A file path: one naming {@code ${basedir}} never matches, as there is no
		 * project directory.
		 */
		@Nullable String path(@Nullable String value) {
			return value != null && value.contains("${basedir}") ? null : of(value);
		}

	}

	/** Injects a profile into a model, the profile's entries winning. */
	private static PomModel inject(PomModel model, PomModel.Profile profile) {
		Map<String, String> properties = new LinkedHashMap<>(model.properties());
		properties.putAll(profile.properties());
		List<PomModel.Dep> managed = model.managedDependencies();
		List<PomModel.Dep> profileManaged = profile.managedDependencies();
		if (profileManaged != null) {
			managed = PomModel.mergeByKey(managed == null ? List.of() : managed, profileManaged, true);
		}
		List<String> modules = new ArrayList<>(model.modules());
		for (String module : profile.modules()) {
			if (!model.modules().contains(module)) {
				modules.add(module);
			}
		}
		PomModel.Build build = profile.build() == null ? model.build()
				: BuildMerger.injectProfile(model.build(), profile.build());
		return model.withContent(model.description(), properties,
				PomModel.mergeByKey(model.dependencies(), profile.dependencies(), true), managed, modules, build,
				AncillaryMerger.injectProfile(model.ancillary(), profile.ancillary()));
	}

	/**
	 * Merges a parent (its own chain already merged into it) into a child. The model
	 * version is neither inherited nor injected ({@code MavenModelMerger}).
	 */
	private static PomModel inherit(PomModel child, PomModel parent) {
		Map<String, String> properties = new LinkedHashMap<>(parent.properties());
		properties.putAll(child.properties());
		List<PomModel.Dep> managed = child.managedDependencies();
		List<PomModel.Dep> parentManaged = parent.managedDependencies();
		if (parentManaged != null) {
			managed = PomModel.mergeByKey(managed == null ? List.of() : managed, parentManaged, false);
		}
		PomModel merged = child.withCoordinates(child.groupId() == null ? parent.groupId() : child.groupId(),
				child.version() == null ? parent.version() : child.version());
		return merged.withContent(child.description() == null ? parent.description() : child.description(), properties,
				PomModel.mergeByKey(child.dependencies(), parent.dependencies(), false), managed, child.modules(),
				BuildMerger.inherit(child.build(), parent.build()),
				AncillaryMerger.inherit(child.ancillary(), parent.ancillary()));
	}

	/**
	 * Expands {@code ${...}} over the merged model, with Maven's value sources in order.
	 */
	private PomModel interpolate(PomModel model, Map<String, String> user, ModelProblems problems) {
		Interpolator interpolator = new Interpolator(List.of(expression -> {
			if (expression.startsWith("project.")) {
				return reflect(model, expression.substring("project.".length()));
			}
			if (expression.startsWith("pom.")) {
				return reflect(model, expression.substring("pom.".length()));
			}
			return null;
		}, Interpolator.of(user), Interpolator.of(model.properties()), Interpolator.of(this.system),
				expression -> this.system.get("env." + expression), expression -> reflect(model, expression)));
		Values values = new Values(interpolator, problems);
		Map<String, String> properties = new LinkedHashMap<>();
		for (Map.Entry<String, String> property : model.properties().entrySet()) {
			properties.put(property.getKey(), values.of(property.getValue()));
		}
		PomModel.Parent parent = model.parent();
		PomModel.Relocation relocation = model.relocation();
		List<String> modules = new ArrayList<>();
		for (String module : model.modules()) {
			modules.add(values.of(module));
		}
		List<PomModel.Dep> managed = model.managedDependencies();
		return new PomModel(values.ofNullable(model.modelVersion()), parent == null ? null
				: new PomModel.Parent(values.ofNullable(parent.groupId()), values.ofNullable(parent.artifactId()),
						values.ofNullable(parent.version()), values.ofNullable(parent.relativePath())),
				values.ofNullable(model.groupId()), values.ofNullable(model.artifactId()),
				values.ofNullable(model.version()), values.ofNullable(model.packaging()),
				values.ofNullable(model.name()), values.ofNullable(model.description()), properties,
				values.of(model.dependencies()), managed == null ? null : values.of(managed), model.profiles(),
				relocation == null ? null
						: new PomModel.Relocation(values.ofNullable(relocation.groupId()),
								values.ofNullable(relocation.artifactId()), values.ofNullable(relocation.version()),
								values.ofNullable(relocation.message())),
				modules, values.of(model.build()), values.of(model.ancillary()));
	}

	/**
	 * The model fields {@code ${project.*}} reaches, read from the merged model before
	 * interpolation (the interpolator expands what they hold). Any other path is
	 * unanswered and stays as written. {@code basedir} is none: without a project
	 * directory Maven answers it from the properties alone.
	 */
	private static @Nullable String reflect(PomModel model, String path) {
		PomModel.Parent parent = model.parent();
		PomModel.Build build = model.build();
		if (path.startsWith("build.")) {
			return build == null ? null : switch (path) {
				case "build.sourceDirectory" -> build.sourceDirectory();
				case "build.scriptSourceDirectory" -> build.scriptSourceDirectory();
				case "build.testSourceDirectory" -> build.testSourceDirectory();
				case "build.outputDirectory" -> build.outputDirectory();
				case "build.testOutputDirectory" -> build.testOutputDirectory();
				case "build.directory" -> build.directory();
				case "build.finalName" -> build.finalName();
				case "build.defaultGoal" -> build.defaultGoal();
				default -> null;
			};
		}
		return switch (path) {
			case "groupId" -> model.groupId();
			case "artifactId" -> model.artifactId();
			case "version" -> model.version();
			case "packaging" -> model.effectivePackaging();
			case "name" -> model.name();
			case "description" -> model.description();
			case "modelVersion" -> model.modelVersion();
			case "parent.groupId" -> parent == null ? null : parent.groupId();
			case "parent.artifactId" -> parent == null ? null : parent.artifactId();
			case "parent.version" -> parent == null ? null : parent.version();
			default -> null;
		};
	}

	/** Interpolates model values, collecting failures as problems. */
	private record Values(Interpolator interpolator, ModelProblems problems) {

		String of(String value) {
			try {
				return this.interpolator.interpolate(value);
			}
			catch (Interpolator.CycleException ex) {
				this.problems.error(String.valueOf(ex.getMessage()));
				return value;
			}
		}

		@Nullable String ofNullable(@Nullable String value) {
			return value == null ? null : of(value);
		}

		PomModel.@Nullable Build of(PomModel.@Nullable Build build) {
			if (build == null) {
				return null;
			}
			List<String> filters = new ArrayList<>();
			for (String filter : build.filters()) {
				filters.add(of(filter));
			}
			List<PomModel.Plugin> management = build.pluginManagement();
			return new PomModel.Build(ofNullable(build.sourceDirectory()), ofNullable(build.scriptSourceDirectory()),
					ofNullable(build.testSourceDirectory()), ofNullable(build.outputDirectory()),
					ofNullable(build.testOutputDirectory()), ofNullable(build.directory()),
					ofNullable(build.finalName()), ofNullable(build.defaultGoal()), resources(build.resources()),
					resources(build.testResources()), filters, plugins(build.plugins()),
					management == null ? null : plugins(management));
		}

		private List<PomModel.Resource> resources(List<PomModel.Resource> resources) {
			List<PomModel.Resource> result = new ArrayList<>();
			for (PomModel.Resource resource : resources) {
				result.add(new PomModel.Resource(ofNullable(resource.directory()), ofNullable(resource.filtering())));
			}
			return result;
		}

		private List<PomModel.Plugin> plugins(List<PomModel.Plugin> plugins) {
			List<PomModel.Plugin> result = new ArrayList<>();
			for (PomModel.Plugin plugin : plugins) {
				List<PomModel.Execution> executions = new ArrayList<>();
				for (PomModel.Execution execution : plugin.executions()) {
					List<String> goals = new ArrayList<>();
					for (String goal : execution.goals()) {
						goals.add(of(goal));
					}
					executions.add(new PomModel.Execution(ofNullable(execution.id()), ofNullable(execution.phase()),
							goals, ofNullable(execution.inherited()), of(execution.configuration())));
				}
				result.add(new PomModel.Plugin(ofNullable(plugin.groupId()), ofNullable(plugin.artifactId()),
						ofNullable(plugin.version()), ofNullable(plugin.inherited()), of(plugin.configuration()),
						executions, ofNullable(plugin.extensions()), of(plugin.dependencies())));
			}
			return result;
		}

		private @Nullable ConfigurationNode of(@Nullable ConfigurationNode configuration) {
			return configuration == null ? null : configuration.map(this::of);
		}

		List<PomModel.Dep> of(List<PomModel.Dep> deps) {
			List<PomModel.Dep> result = new ArrayList<>();
			for (PomModel.Dep dep : deps) {
				List<PomModel.Excl> exclusions = new ArrayList<>();
				for (PomModel.Excl exclusion : dep.exclusions()) {
					exclusions
						.add(new PomModel.Excl(ofNullable(exclusion.groupId()), ofNullable(exclusion.artifactId())));
				}
				result.add(new PomModel.Dep(ofNullable(dep.groupId()), ofNullable(dep.artifactId()),
						ofNullable(dep.version()), ofNullable(dep.type()), ofNullable(dep.classifier()),
						ofNullable(dep.scope()), ofNullable(dep.systemPath()), ofNullable(dep.optional()), exclusions));
			}
			return result;
		}

		PomModel.Ancillary of(PomModel.Ancillary ancillary) {
			PomModel.Distribution distribution = ancillary.distribution();
			List<PomModel.ReportPlugin> reporting = ancillary.reporting();
			List<PomModel.ReportPlugin> reportPlugins = null;
			if (reporting != null) {
				reportPlugins = new ArrayList<>();
				for (PomModel.ReportPlugin plugin : reporting) {
					reportPlugins.add(new PomModel.ReportPlugin(ofNullable(plugin.groupId()),
							ofNullable(plugin.artifactId()), ofNullable(plugin.inherited())));
				}
			}
			return new PomModel.Ancillary(repositories(ancillary.repositories()),
					repositories(ancillary.pluginRepositories()),
					distribution == null ? null : new PomModel.Distribution(ofNullable(distribution.status()),
							repository(distribution.repository()), repository(distribution.snapshotRepository())),
					reportPlugins);
		}

		private List<PomModel.Repo> repositories(List<PomModel.Repo> repositories) {
			List<PomModel.Repo> result = new ArrayList<>();
			for (PomModel.Repo repository : repositories) {
				result.add(repository(repository));
			}
			return result;
		}

		private PomModel.@Nullable Repo repository(PomModel.@Nullable Repo repository) {
			return repository == null ? null : new PomModel.Repo(ofNullable(repository.id()),
					ofNullable(repository.url()), ofNullable(repository.layout()));
		}

	}

	private PomModel importManagement(PomModel model, Map<String, String> user, Set<String> importChain,
			ModelProblems problems) throws MavenResolutionException {
		List<PomModel.Dep> managed = model.managedDependencies();
		if (managed == null) {
			return model;
		}
		String importing = model.groupId() + ":" + model.artifactId() + ":" + model.version();
		importChain.add(importing);
		List<PomModel.Dep> own = new ArrayList<>();
		List<List<PomModel.Dep>> imported = new ArrayList<>();
		for (PomModel.Dep dep : managed) {
			if (!"pom".equals(dep.effectiveType()) || !"import".equals(dep.scope())) {
				own.add(dep);
				continue;
			}
			List<PomModel.Dep> contribution = importedManagement(dep, importing, user, importChain, problems);
			if (contribution != null) {
				imported.add(contribution);
			}
		}
		importChain.remove(importing);
		if (imported.isEmpty()) {
			return model.withDependencies(model.dependencies(), own);
		}
		Map<String, PomModel.Dep> merged = new LinkedHashMap<>();
		for (PomModel.Dep dep : own) {
			merged.put(dep.managementKey(), dep);
		}
		for (List<PomModel.Dep> contribution : imported) {
			for (PomModel.Dep dep : contribution) {
				merged.putIfAbsent(dep.managementKey(), dep);
			}
		}
		return model.withDependencies(model.dependencies(), new ArrayList<>(merged.values()));
	}

	/**
	 * An import's dependency management, the import built at the minimal level whatever
	 * the importer's, its problems joining the importer's.
	 */
	private @Nullable List<PomModel.Dep> importedManagement(PomModel.Dep dep, String importing,
			Map<String, String> user, Set<String> importChain, ModelProblems problems) throws MavenResolutionException {
		String group = dep.groupId();
		String artifact = dep.artifactId();
		String version = dep.version();
		if (group == null || group.isEmpty()) {
			problems.error("'dependencyManagement.dependencies.dependency.groupId' for " + dep.managementKey()
					+ " is missing.");
			return null;
		}
		if (artifact == null || artifact.isEmpty()) {
			problems.error("'dependencyManagement.dependencies.dependency.artifactId' for " + dep.managementKey()
					+ " is missing.");
			return null;
		}
		if (version == null || version.isEmpty()) {
			problems.error("'dependencyManagement.dependencies.dependency.version' for " + dep.managementKey()
					+ " is missing.");
			return null;
		}
		String id = group + ":" + artifact + ":" + version;
		if (importChain.contains(id)) {
			problems.error("The dependencies of type=pom and with scope=import form a cycle: "
					+ String.join(" -> ", importChain) + " -> " + id);
			return null;
		}
		String cacheKey = id + "|" + user.get("packaging");
		Imported cached = this.imports.get(cacheKey);
		if (cached != null) {
			problems.addAll(cached.problems());
			return cached.managed();
		}
		if (VersionConstraint.isRange(version)) {
			// Maven 3.9 resolves an import's version as written, never as a range: the
			// lookup of that literal path is spared, its answer is the same
			throw new MavenResolutionException("Non-resolvable import POM " + id + " in " + importing
					+ ": an import's version range is looked up as a version, which no repository has");
		}
		ModelProblems importProblems = new ModelProblems();
		try {
			PomModel importRaw = readRepository(new Artifact(group, artifact, version, "", "pom"),
					ModelValidator.MINIMAL, importProblems);
			if (importRaw == null) {
				throw new MavenResolutionException(
						"Non-resolvable import POM " + id + " in " + importing + ": no repository has it");
			}
			List<PomModel.Dep> contribution = build(importRaw, null, ModelValidator.MINIMAL, user, importChain,
					importProblems)
				.managedDependencies();
			List<PomModel.Dep> result = contribution == null ? List.of() : contribution;
			this.imports.put(cacheKey, new Imported(result, importProblems.list()));
			problems.addAll(importProblems.list());
			return result;
		}
		catch (InvalidPomException ex) {
			problems.addAll(ex.problems());
			return null;
		}
	}

	/** Dependency management fills what a dependency leaves out, first entry first. */
	private static PomModel injectManagement(PomModel model) {
		List<PomModel.Dep> managed = model.managedDependencies();
		if (managed == null || managed.isEmpty()) {
			return model;
		}
		List<PomModel.Dep> dependencies = new ArrayList<>();
		for (PomModel.Dep dep : model.dependencies()) {
			PomModel.Dep result = dep;
			String key = dep.managementKey();
			for (PomModel.Dep entry : managed) {
				if (entry.managementKey().equals(key)) {
					result = result.with(result.version() == null ? entry.version() : result.version(),
							result.scope() == null ? entry.scope() : result.scope(),
							result.systemPath() == null ? entry.systemPath() : result.systemPath(),
							result.exclusions().isEmpty() ? entry.exclusions() : result.exclusions());
				}
			}
			dependencies.add(result);
		}
		return model.withDependencies(dependencies, managed);
	}

	/**
	 * {@code DefaultModelNormalizer.injectDefaultValues}: an empty scope is
	 * {@code compile}, a plugin's dependencies' too.
	 */
	private static PomModel injectDefaultScope(PomModel model) {
		PomModel.Build build = model.build();
		if (build != null && !build.plugins().isEmpty()) {
			List<PomModel.Plugin> plugins = new ArrayList<>();
			for (PomModel.Plugin plugin : build.plugins()) {
				plugins.add(new PomModel.Plugin(plugin.groupId(), plugin.artifactId(), plugin.version(),
						plugin.inherited(), plugin.configuration(), plugin.executions(), plugin.extensions(),
						withDefaultScope(plugin.dependencies())));
			}
			model = model.withBuild(build.withPlugins(plugins, build.pluginManagement()));
		}
		return model.withDependencies(withDefaultScope(model.dependencies()), model.managedDependencies());
	}

	private static List<PomModel.Dep> withDefaultScope(List<PomModel.Dep> dependencies) {
		List<PomModel.Dep> result = new ArrayList<>();
		for (PomModel.Dep dep : dependencies) {
			result.add(isEmpty(dep.scope()) ? dep.with(dep.version(), "compile", dep.systemPath(), dep.exclusions())
					: dep);
		}
		return result;
	}

}
