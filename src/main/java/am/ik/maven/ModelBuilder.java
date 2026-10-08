package am.ik.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The effective model of a POM read from a repository, built as Maven 3.9's
 * {@code DefaultModelBuilder} builds a dependency's model (validation level minimal, no
 * project directory, no plugin processing):
 * <ol>
 * <li>each POM of the parent chain is read, its duplicate dependencies collapsed (the
 * last declaration wins, at the first one's position), its profiles' activation
 * interpolated against its own properties and its active profiles injected;</li>
 * <li>the chain is merged from the top: group id, version, description and model version
 * are inherited when absent, properties merge with the child winning, dependencies and
 * dependency management merge by management key with the child's entry winning whole, and
 * the parent's entries the child lacks follow the child's own;</li>
 * <li>{@code ${...}} is expanded over the merged model ({@link Interpolator});</li>
 * <li>{@code import}-scoped {@code pom} entries of the dependency management are replaced
 * by the imported POMs' effective dependency management, the model's own entries first,
 * then each import's in order, the first entry of a key winning;</li>
 * <li>dependency management fills each dependency's absent version, scope and system
 * path, and its exclusions when it has none; the optional flag is never managed;</li>
 * <li>an absent scope becomes {@code compile}, and the model is validated.</li>
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

	/** The raw child versions that name the parent's version. */
	private static final Set<String> PARENT_VERSION_REFERENCES = Set.of("${pom.version}", "${project.version}",
			"${pom.parent.version}", "${project.parent.version}");

	private final PomSource source;

	private final Map<String, String> system;

	/**
	 * Raw models by POM: a {@link PomModel}, an {@link InvalidPomException}, or MISSING.
	 */
	private final Map<Artifact, Object> raw = new HashMap<>();

	/** Effective models by POM, the same three kinds of entry. */
	private final Map<Artifact, Object> effective = new HashMap<>();

	/**
	 * The dependency management an import contributes, by imported id and the packaging
	 * its profiles were activated with. Maven's session cache keys it by id alone, which
	 * makes a BOM whose profiles test {@code packaging} answer whatever its first
	 * importer's packaging chose; with the packaging in the key a descriptor is the same
	 * whatever was resolved before it (what Maven answers without a session cache).
	 */
	private final Map<String, List<PomModel.Dep>> imports = new HashMap<>();

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
	 * Returns the effective model of a POM.
	 * @param pom the POM's coordinates
	 * @return the model, or {@code null} when no repository has the POM
	 * @throws InvalidPomException if Maven's model builder would reject the POM
	 * @throws MavenResolutionException if a parent or import cannot be resolved
	 */
	@Nullable PomModel effective(Artifact pom) throws InvalidPomException, MavenResolutionException {
		Object cached = this.effective.get(pom);
		if (cached == null) {
			try {
				PomModel input = raw(pom);
				if (input == null) {
					cached = MISSING;
				}
				else {
					// Maven's 3.9 profile activation context carries the input POM's
					// packaging as a user property, for its parents and imports too.
					cached = build(input, Map.of("packaging", input.effectivePackaging()), new LinkedHashSet<>());
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
	 * shipped inside a jar -- built like a repository POM's, its parents and imports read
	 * from the source.
	 * @param pom the POM's bytes
	 * @return the model
	 * @throws InvalidPomException if Maven's model builder would reject the POM
	 * @throws MavenResolutionException if a parent or import cannot be resolved
	 */
	PomModel effective(byte[] pom) throws InvalidPomException, MavenResolutionException {
		PomModel input = PomReader.read(pom);
		return build(input, Map.of("packaging", input.effectivePackaging()), new LinkedHashSet<>());
	}

	private @Nullable PomModel raw(Artifact pom) throws InvalidPomException, MavenResolutionException {
		Object cached = this.raw.get(pom);
		if (cached == null) {
			byte[] bytes = this.source.read(pom);
			if (bytes == null) {
				cached = MISSING;
			}
			else {
				try {
					cached = PomReader.read(bytes);
				}
				catch (InvalidPomException ex) {
					cached = ex;
				}
			}
			this.raw.put(pom, cached);
		}
		if (cached == MISSING) {
			return null;
		}
		if (cached instanceof InvalidPomException invalid) {
			throw new InvalidPomException(invalid.problems());
		}
		return (PomModel) cached;
	}

	private PomModel build(PomModel input, Map<String, String> user, Set<String> importChain)
			throws InvalidPomException, MavenResolutionException {
		List<String> problems = new ArrayList<>();
		List<PomModel> lineage = new ArrayList<>();
		Set<String> parentIds = new LinkedHashSet<>();
		PomModel current = input;
		// a fatal problem Maven reports on the next parent it reads
		boolean fatalPending = false;
		while (true) {
			// A raw-model failure is fatal and stops the build; the errors collected so
			// far (profile activation) only invalidate it at the end, as in Maven.
			List<String> fatal = new ArrayList<>();
			validateRaw(current, fatal);
			if (!fatal.isEmpty()) {
				problems.addAll(fatal);
				throw new InvalidPomException(problems);
			}
			lineage.add(activateProfiles(mergeDuplicates(current), user, problems));
			PomModel.Parent parent = current.parent();
			if (parent == null) {
				break;
			}
			String parentGroup = nonNull(parent.groupId());
			String parentArtifact = nonNull(parent.artifactId());
			String writtenVersion = nonNull(parent.version());
			String writtenChildId = (current.groupId() == null ? parentGroup : current.groupId()) + ":"
					+ current.artifactId() + ":" + (current.version() == null ? writtenVersion : current.version());
			String parentVersion = parentVersion(parentGroup, parentArtifact, writtenVersion, writtenChildId);
			String parentId = parentGroup + ":" + parentArtifact + ":" + parentVersion;
			String childId = (current.groupId() == null ? parentGroup : current.groupId()) + ":" + current.artifactId()
					+ ":" + (current.version() == null ? parentVersion : current.version());
			PomModel parentModel = raw(new Artifact(parentGroup, parentArtifact, parentVersion, "", "pom"));
			if (parentModel == null) {
				throw new MavenResolutionException(
						"Non-resolvable parent POM " + parentId + " for " + childId + ": no repository has it");
			}
			if (fatalPending) {
				throw new InvalidPomException(problems);
			}
			if (!parentVersion.equals(writtenVersion)
					&& (current.version() == null || PARENT_VERSION_REFERENCES.contains(current.version()))) {
				// a version range resolved: the child cannot inherit what it resolved to
				problems.add("Version must be a constant");
				fatalPending = true;
			}
			if (lineage.size() == 1) {
				// Maven records the child, its group and version inherited, then each
				// grandparent onwards; the first parent itself is never recorded.
				parentIds.add(childId);
			}
			else if (!parentIds.add(parentId)) {
				problems.add("The parents form a cycle: " + String.join(" -> ", parentIds) + " -> " + parentId);
				throw new InvalidPomException(problems);
			}
			current = parentModel;
		}
		PomModel model = lineage.get(lineage.size() - 1);
		for (int i = lineage.size() - 2; i >= 0; i--) {
			model = inherit(lineage.get(i), model);
		}
		model = interpolate(model, user, problems);
		model = importManagement(model, user, importChain, problems);
		model = injectManagement(model);
		model = injectDefaultScope(model);
		validateEffective(model, problems);
		if (!problems.isEmpty()) {
			throw new InvalidPomException(problems);
		}
		return model;
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

	private static String nonNull(@Nullable String value) {
		return value == null ? "" : value;
	}

	private static boolean isEmpty(@Nullable String value) {
		return value == null || value.isEmpty();
	}

	/** Maven's raw-model checks at validation level minimal; each failure is fatal. */
	private static void validateRaw(PomModel model, List<String> problems) {
		PomModel.Parent parent = model.parent();
		if (parent == null) {
			return;
		}
		if (isEmpty(parent.groupId())) {
			problems.add("'parent.groupId' is missing.");
		}
		if (isEmpty(parent.artifactId())) {
			problems.add("'parent.artifactId' is missing.");
		}
		if (isEmpty(parent.version())) {
			problems.add("'parent.version' is missing.");
		}
		if (nonNull(parent.groupId()).equals(nonNull(model.groupId()))
				&& nonNull(parent.artifactId()).equals(nonNull(model.artifactId()))) {
			problems.add("'parent.artifactId' must be changed, the parent element cannot have the same "
					+ "groupId:artifactId as the project.");
		}
	}

	/** A dependency declared twice keeps its first position and its last declaration. */
	private static PomModel mergeDuplicates(PomModel model) {
		Map<String, PomModel.Dep> byKey = new LinkedHashMap<>();
		for (PomModel.Dep dep : model.dependencies()) {
			byKey.put(dep.managementKey(), dep);
		}
		if (byKey.size() == model.dependencies().size()) {
			return model;
		}
		return model.withDependencies(new ArrayList<>(byKey.values()), model.managedDependencies());
	}

	private PomModel activateProfiles(PomModel model, Map<String, String> user, List<String> problems) {
		if (model.profiles().isEmpty()) {
			return model;
		}
		// The activation is interpolated against the POM's own properties, not the
		// inherited ones, then the user and system properties.
		Interpolator activation = new Interpolator(
				List.of(Interpolator.of(model.properties()), Interpolator.of(user), Interpolator.of(this.system)));
		List<PomModel.Profile> interpolated = new ArrayList<>();
		for (PomModel.Profile profile : model.profiles()) {
			interpolated.add(profile.withActivation(interpolateActivation(profile.activation(), activation, problems)));
		}
		PomModel result = model;
		for (PomModel.Profile profile : ProfileActivator.active(interpolated, user, this.system, problems)) {
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
		return model.withContent(model.modelVersion(), model.description(), properties,
				PomModel.mergeByKey(model.dependencies(), profile.dependencies(), true), managed, modules);
	}

	/** Merges a parent (its own chain already merged into it) into a child. */
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
		return merged.withContent(child.modelVersion() == null ? parent.modelVersion() : child.modelVersion(),
				child.description() == null ? parent.description() : child.description(), properties,
				PomModel.mergeByKey(child.dependencies(), parent.dependencies(), false), managed, child.modules());
	}

	/**
	 * Expands {@code ${...}} over the merged model, with Maven's value sources in order.
	 */
	private PomModel interpolate(PomModel model, Map<String, String> user, List<String> problems) {
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
		return new PomModel(values.ofNullable(model.modelVersion()),
				parent == null ? null
						: new PomModel.Parent(values.ofNullable(parent.groupId()),
								values.ofNullable(parent.artifactId()), values.ofNullable(parent.version())),
				values.ofNullable(model.groupId()), values.ofNullable(model.artifactId()),
				values.ofNullable(model.version()), values.ofNullable(model.packaging()),
				values.ofNullable(model.name()), values.ofNullable(model.description()), properties,
				values.of(model.dependencies()), managed == null ? null : values.of(managed), model.profiles(),
				relocation == null ? null
						: new PomModel.Relocation(values.ofNullable(relocation.groupId()),
								values.ofNullable(relocation.artifactId()), values.ofNullable(relocation.version()),
								values.ofNullable(relocation.message())),
				modules);
	}

	/**
	 * The model fields {@code ${project.*}} reaches, read from the merged model before
	 * interpolation (the interpolator expands what they hold). Any other path is
	 * unanswered and stays as written.
	 */
	private static @Nullable String reflect(PomModel model, String path) {
		PomModel.Parent parent = model.parent();
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
	private record Values(Interpolator interpolator, List<String> problems) {

		String of(String value) {
			try {
				return this.interpolator.interpolate(value);
			}
			catch (Interpolator.CycleException ex) {
				this.problems.add(ex.getMessage());
				return value;
			}
		}

		@Nullable String ofNullable(@Nullable String value) {
			return value == null ? null : of(value);
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

	}

	private PomModel importManagement(PomModel model, Map<String, String> user, Set<String> importChain,
			List<String> problems) throws MavenResolutionException {
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

	private @Nullable List<PomModel.Dep> importedManagement(PomModel.Dep dep, String importing,
			Map<String, String> user, Set<String> importChain, List<String> problems) throws MavenResolutionException {
		String group = dep.groupId();
		String artifact = dep.artifactId();
		String version = dep.version();
		if (group == null || group.isEmpty()) {
			problems.add("'dependencyManagement.dependencies.dependency.groupId' for " + dep.managementKey()
					+ " is missing.");
			return null;
		}
		if (artifact == null || artifact.isEmpty()) {
			problems.add("'dependencyManagement.dependencies.dependency.artifactId' for " + dep.managementKey()
					+ " is missing.");
			return null;
		}
		if (version == null || version.isEmpty()) {
			problems.add("'dependencyManagement.dependencies.dependency.version' for " + dep.managementKey()
					+ " is missing.");
			return null;
		}
		String id = group + ":" + artifact + ":" + version;
		if (importChain.contains(id)) {
			problems.add("The dependencies of type=pom and with scope=import form a cycle: "
					+ String.join(" -> ", importChain) + " -> " + id);
			return null;
		}
		String cacheKey = id + "|" + user.get("packaging");
		List<PomModel.Dep> cached = this.imports.get(cacheKey);
		if (cached != null) {
			return cached;
		}
		if (VersionConstraint.isRange(version)) {
			// Maven 3.9 resolves an import's version as written, never as a range: the
			// lookup of that literal path is spared, its answer is the same
			throw new MavenResolutionException("Non-resolvable import POM " + id + " in " + importing
					+ ": an import's version range is looked up as a version, which no repository has");
		}
		try {
			PomModel importRaw = raw(new Artifact(group, artifact, version, "", "pom"));
			if (importRaw == null) {
				throw new MavenResolutionException(
						"Non-resolvable import POM " + id + " in " + importing + ": no repository has it");
			}
			List<PomModel.Dep> contribution = build(importRaw, user, importChain).managedDependencies();
			List<PomModel.Dep> result = contribution == null ? List.of() : contribution;
			this.imports.put(cacheKey, result);
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

	private static PomModel injectDefaultScope(PomModel model) {
		List<PomModel.Dep> dependencies = new ArrayList<>();
		for (PomModel.Dep dep : model.dependencies()) {
			dependencies.add(isEmpty(dep.scope())
					? dep.with(dep.version(), "compile", dep.systemPath(), dep.exclusions()) : dep);
		}
		return model.withDependencies(dependencies, model.managedDependencies());
	}

	/** Maven's effective-model checks at validation level minimal. */
	private static void validateEffective(PomModel model, List<String> problems) {
		if (isEmpty(model.modelVersion())) {
			problems.add("'modelVersion' is missing.");
		}
		validateId("groupId", model.groupId(), null, problems);
		validateId("artifactId", model.artifactId(), null, problems);
		if (isEmpty(model.packaging()) && model.packaging() != null) {
			problems.add("'packaging' is missing.");
		}
		if (!model.modules().isEmpty() && !"pom".equals(model.effectivePackaging())) {
			problems.add("'packaging' with value '" + model.effectivePackaging()
					+ "' is invalid. Aggregator projects require 'pom' as packaging.");
		}
		if (isEmpty(model.version())) {
			problems.add("'version' is missing.");
		}
		for (PomModel.Dep dep : model.dependencies()) {
			validateDependency(dep, false, problems);
		}
		List<PomModel.Dep> managed = model.managedDependencies();
		if (managed != null) {
			for (PomModel.Dep dep : managed) {
				validateDependency(dep, true, problems);
			}
		}
	}

	private static void validateDependency(PomModel.Dep dep, boolean management, List<String> problems) {
		String prefix = management ? "dependencyManagement.dependencies.dependency." : "dependencies.dependency.";
		String key = dep.managementKey();
		validateId(prefix + "artifactId", dep.artifactId(), key, problems);
		validateId(prefix + "groupId", dep.groupId(), key, problems);
		if (!management) {
			if (dep.type() != null && dep.type().isEmpty()) {
				problems.add("'" + prefix + "type' for " + key + " is missing.");
			}
			if (isEmpty(dep.version())) {
				problems.add("'" + prefix + "version' for " + key + " is missing.");
			}
		}
		String systemPath = dep.systemPath();
		if ("system".equals(dep.scope())) {
			if (systemPath == null || systemPath.isEmpty()) {
				problems.add("'" + prefix + "systemPath' for " + key + " is missing.");
			}
			else if (!new File(systemPath).isAbsolute()) {
				problems.add("'" + prefix + "systemPath' for " + key + " must specify an absolute path but is "
						+ systemPath);
			}
		}
		else if (systemPath != null && !systemPath.isEmpty()) {
			problems.add("'" + prefix + "systemPath' for " + key
					+ " must be omitted. This field may only be specified for a dependency with system scope.");
		}
	}

	private static void validateId(String field, @Nullable String id, @Nullable String key, List<String> problems) {
		String subject = "'" + field + "'" + (key == null ? "" : " for " + key);
		if (id == null || id.isEmpty()) {
			problems.add(subject + " is missing.");
			return;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			boolean valid = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-'
					|| c == '_' || c == '.';
			if (!valid) {
				problems.add(subject + " with value '" + id + "' does not match a valid id pattern.");
				return;
			}
		}
	}

}
