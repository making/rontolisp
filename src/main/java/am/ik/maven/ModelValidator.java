package am.ik.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import am.ik.maven.ModelProblem.Severity;
import org.jspecify.annotations.Nullable;

/**
 * Maven 3.9.16's {@code DefaultModelValidator}, ported check by check over the fields
 * {@link PomModel} holds, at a validation level ({@code ModelBuildingRequest}'s): the
 * minimal level a dependency's POM is built at, {@link #MAVEN_2_0} for a parent read from
 * a repository under a stricter request, and {@link #STRICT} -- a bare
 * {@code DefaultModelBuildingRequest}'s, what tools.deps builds a project's
 * {@code pom.xml} and a jar's own POM at. A check's severity at a level is Maven's
 * ({@code errOn30} and {@code errOn31}: a warning below that level, an error from it).
 */
final class ModelValidator {

	/** {@code VALIDATION_LEVEL_MINIMAL}. */
	static final int MINIMAL = 0;

	/** {@code VALIDATION_LEVEL_MAVEN_2_0}. */
	static final int MAVEN_2_0 = 20;

	/** {@code VALIDATION_LEVEL_MAVEN_3_0}. */
	static final int MAVEN_3_0 = 30;

	/** {@code VALIDATION_LEVEL_MAVEN_3_1}. */
	static final int MAVEN_3_1 = 31;

	/**
	 * {@code VALIDATION_LEVEL_STRICT}, a {@code DefaultModelBuildingRequest}'s default.
	 */
	static final int STRICT = MAVEN_3_0;

	private static final Pattern CI_FRIENDLY_EXPRESSION = Pattern.compile("\\$\\{(.+?)}");

	private static final Pattern EXPRESSION_PROJECT_NAME_PATTERN = Pattern.compile("\\$\\{(project.+?)}");

	private static final Set<String> CI_FRIENDLY_PROPERTIES = Set.of("revision", "changelist", "sha1");

	private static final String ILLEGAL_CHARS = "\\/:\"<>|?*";

	private static final String EMPTY = "";

	private ModelValidator() {
	}

	/**
	 * {@code validateRawModel}: a POM as read, before anything is merged into it.
	 * @param m the raw model
	 * @param level the validation level
	 * @param problems where problems go
	 */
	static void validateRaw(PomModel m, int level, ModelProblems problems) {
		PomModel.Parent parent = m.parent();
		if (parent != null) {
			notEmpty("parent.groupId", Severity.FATAL, parent.groupId(), problems);
			notEmpty("parent.artifactId", Severity.FATAL, parent.artifactId(), problems);
			notEmpty("parent.version", Severity.FATAL, parent.version(), problems);
			if (clean(parent.groupId()).equals(clean(m.groupId()))
					&& clean(parent.artifactId()).equals(clean(m.artifactId()))) {
				violation(Severity.FATAL, "parent.artifactId", null,
						"must be changed, the parent element cannot have the same groupId:artifactId as the project.",
						problems);
			}
			if (clean(parent.version()).equals("LATEST") || clean(parent.version()).equals("RELEASE")) {
				violation(Severity.WARNING, "parent.version", null,
						"is either LATEST or RELEASE (both of them are being deprecated)", problems);
			}
		}
		if (level == MINIMAL) {
			Set<@Nullable String> ids = new HashSet<>();
			for (PomModel.Profile profile : m.profiles()) {
				if (!ids.add(profile.id())) {
					violation(Severity.WARNING, "profiles.profile.id", null,
							"Duplicate activation for profile " + profile.id(), problems);
				}
			}
			return;
		}
		if (level < MAVEN_2_0) {
			return;
		}
		Severity errOn30 = severity(level, MAVEN_3_0);
		notEmpty("modelVersion", Severity.ERROR, m.modelVersion(), problems);
		modelVersion(m.modelVersion(), problems);
		noExpression("groupId", m.groupId(), problems);
		if (parent == null) {
			notEmpty("groupId", Severity.FATAL, m.groupId(), problems);
		}
		noExpression("artifactId", m.artifactId(), problems);
		notEmpty("artifactId", Severity.FATAL, m.artifactId(), problems);
		versionNoExpression(m.version(), problems);
		if (parent == null) {
			notEmpty("version", Severity.FATAL, m.version(), problems);
		}
		rawDependencies(m.dependencies(), "dependencies.dependency.", EMPTY, level, problems);
		selfReferencing(m, problems);
		List<PomModel.Dep> management = m.managedDependencies();
		if (management != null) {
			rawDependencies(management, "dependencyManagement.dependencies.dependency.", EMPTY, level, problems);
		}
		rawRepositories(m.ancillary().repositories(), "repositories.repository.", EMPTY, level, problems);
		rawRepositories(m.ancillary().pluginRepositories(), "pluginRepositories.pluginRepository.", EMPTY, level,
				problems);
		PomModel.Build build = m.build();
		if (build != null) {
			rawPlugins(build.plugins(), "build.plugins.plugin.", EMPTY, level, problems);
			List<PomModel.Plugin> managed = build.pluginManagement();
			if (managed != null) {
				rawPlugins(managed, "build.pluginManagement.plugins.plugin.", EMPTY, level, problems);
			}
		}
		Set<@Nullable String> profileIds = new HashSet<>();
		for (PomModel.Profile profile : m.profiles()) {
			String prefix = "profiles.profile[" + profile.id() + "].";
			if (!profileIds.add(profile.id())) {
				violation(errOn30, "profiles.profile.id", null,
						"must be unique but found duplicate profile with id " + profile.id(), problems);
			}
			activation(profile.activation(), prefix, problems);
			rawDependencies(profile.dependencies(), prefix, "dependencies.dependency.", level, problems);
			List<PomModel.Dep> profileManagement = profile.managedDependencies();
			if (profileManagement != null) {
				rawDependencies(profileManagement, prefix, "dependencyManagement.dependencies.dependency.", level,
						problems);
			}
			rawRepositories(profile.ancillary().repositories(), prefix, "repositories.repository.", level, problems);
			rawRepositories(profile.ancillary().pluginRepositories(), prefix, "pluginRepositories.pluginRepository.",
					level, problems);
			PomModel.Build profileBuild = profile.build();
			if (profileBuild != null) {
				rawPlugins(profileBuild.plugins(), prefix, "plugins.plugin.", level, problems);
				List<PomModel.Plugin> managed = profileBuild.pluginManagement();
				if (managed != null) {
					rawPlugins(managed, prefix, "pluginManagement.plugins.plugin.", level, problems);
				}
			}
		}
	}

	/**
	 * {@code validateEffectiveModel}: the model built.
	 * @param m the effective model
	 * @param level the validation level
	 * @param system the system properties ({@code java.home} words a missing system
	 * path's warning)
	 * @param problems where problems go
	 */
	static void validateEffective(PomModel m, int level, Map<String, String> system, ModelProblems problems) {
		notEmpty("modelVersion", Severity.ERROR, m.modelVersion(), problems);
		id(EMPTY, "groupId", Severity.ERROR, m.groupId(), null, problems);
		id(EMPTY, "artifactId", Severity.ERROR, m.artifactId(), null, problems);
		notEmpty("packaging", Severity.ERROR, m.effectivePackaging(), problems);
		if (!m.modules().isEmpty()) {
			if (!"pom".equals(m.effectivePackaging())) {
				violation(Severity.ERROR, "packaging", null, "with value '" + m.effectivePackaging()
						+ "' is invalid. Aggregator projects require 'pom' as packaging.", problems);
			}
			for (int i = 0; i < m.modules().size(); i++) {
				if (m.modules().get(i).isBlank()) {
					violation(Severity.ERROR, "modules.module[" + i + "]", null,
							"has been specified without a path to the project directory.", problems);
				}
			}
		}
		notEmpty("version", Severity.ERROR, m.version(), problems);
		Severity errOn30 = severity(level, MAVEN_3_0);
		effectiveDependencies(m, m.dependencies(), false, level, system, problems);
		List<PomModel.Dep> management = m.managedDependencies();
		if (management != null) {
			effectiveDependencies(m, management, true, level, system, problems);
		}
		if (level < MAVEN_2_0) {
			return;
		}
		Set<String> modules = new HashSet<>();
		for (int i = 0; i < m.modules().size(); i++) {
			String module = m.modules().get(i);
			if (!modules.add(module)) {
				violation(Severity.ERROR, "modules.module[" + i + "]", null,
						"specifies duplicate child module " + module, problems);
			}
		}
		Severity errOn31 = severity(level, MAVEN_3_1);
		bannedCharacters(EMPTY, "version", errOn31, m.version(), null, problems);
		String version = m.version();
		if (version != null && !version.isEmpty() && version.endsWith("SNAPSHOT") && !version.endsWith("-SNAPSHOT")) {
			violation(errOn31, "version", null,
					"uses an unsupported snapshot version format, should be '*-SNAPSHOT' instead.", problems);
		}
		if (hasExpression(version)) {
			violation(
					Boolean.parseBoolean(m.properties().get("maven.build.allowExpressionInEffectiveProjectVersion"))
							? Severity.WARNING : Severity.ERROR,
					"version", null, "must be a constant version but is '" + version + "'.", problems);
		}
		PomModel.Build build = m.build();
		if (build != null) {
			for (PomModel.Plugin p : build.plugins()) {
				notEmpty("build.plugins.plugin.artifactId", Severity.ERROR, p.artifactId(), problems);
				notEmpty("build.plugins.plugin.groupId", Severity.ERROR, p.groupId(), problems);
				pluginVersion(p, errOn30, problems);
				bool("build.plugins.plugin.inherited", EMPTY, errOn30, p.inherited(), p.key(), problems);
				bool("build.plugins.plugin.extensions", EMPTY, errOn30, p.extensions(), p.key(), problems);
				pluginDependencies(p, level, errOn30, system, problems);
			}
			resources(build.resources(), "build.resources.resource.", errOn30, problems);
			resources(build.testResources(), "build.testResources.testResource.", errOn30, problems);
		}
		List<PomModel.ReportPlugin> reporting = m.ancillary().reporting();
		if (reporting != null) {
			for (PomModel.ReportPlugin p : reporting) {
				notEmpty("reporting.plugins.plugin.artifactId", Severity.ERROR, p.artifactId(), problems);
				notEmpty("reporting.plugins.plugin.groupId", Severity.ERROR, p.groupId(), problems);
			}
		}
		for (PomModel.Repo repository : m.ancillary().repositories()) {
			effectiveRepository(repository, "repositories.repository.", errOn31, problems);
		}
		for (PomModel.Repo repository : m.ancillary().pluginRepositories()) {
			effectiveRepository(repository, "pluginRepositories.pluginRepository.", errOn31, problems);
		}
		PomModel.Distribution distribution = m.ancillary().distribution();
		if (distribution != null) {
			if (distribution.status() != null) {
				violation(Severity.ERROR, "distributionManagement.status", null, "must not be specified.", problems);
			}
			effectiveRepository(distribution.repository(), "distributionManagement.repository.", errOn31, problems);
			effectiveRepository(distribution.snapshotRepository(), "distributionManagement.snapshotRepository.",
					errOn31, problems);
		}
	}

	private static void activation(PomModel.@Nullable Activation activation, String prefix, ModelProblems problems) {
		if (activation == null) {
			return;
		}
		PomModel.FileCheck file = activation.file();
		if (file != null) {
			activationValue(prefix, "activation.file.exists", file.exists(), problems);
			activationValue(prefix, "activation.file.missing", file.missing(), problems);
		}
		PomModel.Os os = activation.os();
		if (os != null) {
			activationValue(prefix, "activation.os.arch", os.arch(), problems);
			activationValue(prefix, "activation.os.family", os.family(), problems);
			activationValue(prefix, "activation.os.name", os.name(), problems);
			activationValue(prefix, "activation.os.version", os.version(), problems);
		}
		PomModel.Property property = activation.property();
		if (property != null) {
			activationValue(prefix, "activation.property.name", property.name(), problems);
			activationValue(prefix, "activation.property.value", property.value(), problems);
		}
		if (activation.jdk() != null) {
			activationValue(prefix, "activation.jdk", activation.jdk(), problems);
		}
	}

	/** {@code validate30RawProfileActivation}'s check of one value. */
	private static void activationValue(String prefix, String path, @Nullable String value, ModelProblems problems) {
		if (value == null || !value.contains("${project.")) {
			return;
		}
		Matcher matcher = EXPRESSION_PROJECT_NAME_PATTERN.matcher(value);
		while (matcher.find()) {
			String propertyName = matcher.group(0);
			if (path.startsWith("activation.file.") && "${project.basedir}".equals(propertyName)) {
				continue;
			}
			violation(
					Severity.WARNING, prefix + path, null, "Failed to interpolate profile activation property " + value
							+ ": " + propertyName + " expressions are not supported during profile activation.",
					problems);
		}
	}

	private static void rawPlugins(List<PomModel.Plugin> plugins, String prefix, String prefix2, int level,
			ModelProblems problems) {
		Severity errOn31 = severity(level, MAVEN_3_1);
		Set<String> index = new HashSet<>();
		String field = prefix + prefix2 + "(groupId:artifactId)";
		for (PomModel.Plugin plugin : plugins) {
			if (plugin.groupId() == null || plugin.groupId().isBlank()) {
				violation(Severity.FATAL, field, null, "groupId of a plugin must be defined. ", problems);
			}
			if (plugin.artifactId() == null || plugin.artifactId().isBlank()) {
				violation(Severity.FATAL, field, null, "artifactId of a plugin must be defined. ", problems);
			}
			if (plugin.version() != null && plugin.version().isBlank()) {
				violation(Severity.FATAL, field, null, "version of a plugin must be defined. ", problems);
			}
			if (!index.add(plugin.key())) {
				violation(errOn31, field, null,
						"must be unique but found duplicate declaration of plugin " + plugin.key(), problems);
			}
			Set<@Nullable String> executionIds = new HashSet<>();
			for (PomModel.Execution execution : plugin.executions()) {
				if (!executionIds.add(execution.id())) {
					violation(Severity.ERROR, prefix + prefix2 + "[" + plugin.key() + "].executions.execution.id", null,
							"must be unique but found duplicate execution with id " + execution.id(), problems);
				}
			}
		}
	}

	private static void rawDependencies(List<PomModel.Dep> dependencies, String prefix, String prefix2, int level,
			ModelProblems problems) {
		Severity errOn30 = severity(level, MAVEN_3_0);
		Severity errOn31 = severity(level, MAVEN_3_1);
		Map<String, PomModel.Dep> index = new HashMap<>();
		for (PomModel.Dep dependency : dependencies) {
			String key = dependency.managementKey();
			if ("import".equals(dependency.scope())) {
				if (!"pom".equals(dependency.effectiveType())) {
					violation(Severity.WARNING, prefix + prefix2 + "type", key,
							"must be 'pom' to import the managed dependencies.", problems);
				}
				else if (dependency.classifier() != null && !dependency.classifier().isEmpty()) {
					violation(errOn30, prefix + prefix2 + "classifier", key,
							"must be empty, imported POM cannot have a classifier.", problems);
				}
			}
			else if ("system".equals(dependency.scope())) {
				if (level >= MAVEN_3_1) {
					violation(Severity.WARNING, prefix + prefix2 + "scope", key,
							"declares usage of deprecated 'system' scope ", problems);
				}
				String systemPath = dependency.systemPath();
				if (systemPath != null && !systemPath.isEmpty()) {
					if (!hasExpression(systemPath)) {
						violation(Severity.WARNING, prefix + prefix2 + "systemPath", key,
								"should use a variable instead of a hard-coded path " + systemPath, problems);
					}
					else if (systemPath.contains("${basedir}") || systemPath.contains("${project.basedir}")) {
						violation(Severity.WARNING, prefix + prefix2 + "systemPath", key,
								"should not point at files within the project directory, " + systemPath
										+ " will be unresolvable by dependent projects",
								problems);
					}
				}
			}
			if (clean(dependency.version()).equals("LATEST") || clean(dependency.version()).equals("RELEASE")) {
				violation(Severity.WARNING, prefix + prefix2 + "version", key,
						"is either LATEST or RELEASE (both of them are being deprecated)", problems);
			}
			PomModel.Dep existing = index.get(key);
			if (existing != null) {
				String message = clean(existing.version()).equals(clean(dependency.version()))
						? "duplicate declaration of version " + orUnknown(dependency.version())
						: "version " + orUnknown(existing.version()) + " vs " + orUnknown(dependency.version());
				violation(errOn31, prefix + prefix2 + "(groupId:artifactId:type:classifier)", null,
						"must be unique: " + key + " -> " + message, problems);
			}
			else {
				index.put(key, dependency);
			}
		}
	}

	/** {@code validate20RawDependenciesSelfReferencing}. */
	private static void selfReferencing(PomModel m, ModelProblems problems) {
		String modelKey = m.groupId() + ":" + m.artifactId() + ":" + m.version();
		for (PomModel.Dep dependency : m.dependencies()) {
			String key = dependency.groupId() + ":" + dependency.artifactId() + ":" + dependency.version()
					+ (dependency.classifier() != null ? ":" + dependency.classifier() : EMPTY);
			if (key.equals(modelKey)) {
				violation(Severity.FATAL, "dependencies.dependency[" + key + "]", key, "is referencing itself.",
						problems);
			}
		}
	}

	private static void rawRepositories(List<PomModel.Repo> repositories, String prefix, String prefix2, int level,
			ModelProblems problems) {
		Map<@Nullable String, PomModel.Repo> index = new HashMap<>();
		for (PomModel.Repo repository : repositories) {
			notEmpty(prefix + prefix2 + "id", Severity.ERROR, repository.id(), problems);
			notEmpty(prefix + prefix2 + "[" + repository.id() + "].url", Severity.ERROR, repository.url(), problems);
			PomModel.Repo existing = index.get(repository.id());
			if (existing != null) {
				violation(severity(level, MAVEN_3_0), prefix + prefix2 + "id", null,
						"must be unique: " + repository.id() + " -> " + existing.url() + " vs " + repository.url(),
						problems);
			}
			else {
				index.put(repository.id(), repository);
			}
		}
	}

	private static void effectiveDependencies(PomModel m, List<PomModel.Dep> dependencies, boolean management,
			int level, Map<String, String> system, ModelProblems problems) {
		Severity errOn30 = severity(level, MAVEN_3_0);
		String prefix = management ? "dependencyManagement.dependencies.dependency." : "dependencies.dependency.";
		for (PomModel.Dep d : dependencies) {
			effectiveDependency(d, management, prefix, level, system, problems);
			if (level < MAVEN_2_0) {
				continue;
			}
			String key = d.managementKey();
			bool(prefix, "optional", errOn30, d.optional(), key, problems);
			if (!management) {
				version(prefix, "version", errOn30, d.version(), key, problems);
				oneOf(prefix, "scope", Severity.WARNING, d.scope(), key, problems, "provided", "compile", "runtime",
						"test", "system");
				String self = d.groupId() + ":" + d.artifactId() + ":" + d.version()
						+ (d.classifier() != null ? ":" + d.classifier() : EMPTY);
				if (self.equals(m.groupId() + ":" + m.artifactId() + ":" + m.version())) {
					violation(Severity.FATAL, prefix + "[" + self + "]", self, "is referencing itself.", problems);
				}
			}
			else {
				oneOf(prefix, "scope", Severity.WARNING, d.scope(), key, problems, "provided", "compile", "runtime",
						"test", "system", "import");
			}
		}
	}

	private static void pluginDependencies(PomModel.Plugin plugin, int level, Severity errOn30,
			Map<String, String> system, ModelProblems problems) {
		String prefix = "build.plugins.plugin[" + plugin.key() + "].dependencies.dependency.";
		for (PomModel.Dep d : plugin.dependencies()) {
			effectiveDependency(d, false, prefix, level, system, problems);
			version(prefix, "version", errOn30, d.version(), d.managementKey(), problems);
			oneOf(prefix, "scope", errOn30, d.scope(), d.managementKey(), problems, "compile", "runtime", "system");
		}
	}

	private static void effectiveDependency(PomModel.Dep d, boolean management, String prefix, int level,
			Map<String, String> system, ModelProblems problems) {
		String key = d.managementKey();
		id(prefix, "artifactId", Severity.ERROR, d.artifactId(), key, problems);
		id(prefix, "groupId", Severity.ERROR, d.groupId(), key, problems);
		if (!management) {
			if (d.type() != null && d.type().isEmpty()) {
				violation(Severity.ERROR, prefix + "type", key, "is missing.", problems);
			}
			if (d.version() == null || d.version().isEmpty()) {
				violation(Severity.ERROR, prefix + "version", key, "is missing.", problems);
			}
		}
		String systemPath = d.systemPath();
		if ("system".equals(d.scope())) {
			if (systemPath == null || systemPath.isEmpty()) {
				violation(Severity.ERROR, prefix + "systemPath", key, "is missing.", problems);
			}
			else {
				File file = new File(systemPath);
				if (!file.isAbsolute()) {
					violation(Severity.ERROR, prefix + "systemPath", key,
							"must specify an absolute path but is " + systemPath, problems);
				}
				else if (!file.isFile()) {
					String message = "refers to a non-existing file " + file.getAbsolutePath();
					String normalized = systemPath.replace('/', File.separatorChar).replace('\\', File.separatorChar);
					String jdkHome = system.getOrDefault("java.home", EMPTY) + File.separator + "..";
					if (normalized.startsWith(jdkHome)) {
						message += ". Please verify that you run Maven using a JDK and not just a JRE.";
					}
					violation(Severity.WARNING, prefix + "systemPath", key, message, problems);
				}
			}
		}
		else if (systemPath != null && !systemPath.isEmpty()) {
			violation(Severity.ERROR, prefix + "systemPath", key,
					"must be omitted. This field may only be specified for a dependency with system scope.", problems);
		}
		if (level < MAVEN_2_0) {
			return;
		}
		for (PomModel.Excl exclusion : d.exclusions()) {
			boolean wildcards = level >= MAVEN_3_0;
			exclusionId(prefix + "exclusions.exclusion.groupId", exclusion.groupId(), key, wildcards, problems);
			exclusionId(prefix + "exclusions.exclusion.artifactId", exclusion.artifactId(), key, wildcards, problems);
		}
	}

	private static void exclusionId(String field, @Nullable String id, String key, boolean wildcards,
			ModelProblems problems) {
		if (id == null || id.isEmpty()) {
			violation(Severity.WARNING, field, key, "is missing.", problems);
			return;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			if (!isIdCharacter(c) && !(wildcards && (c == '?' || c == '*'))) {
				violation(Severity.WARNING, field, key, "with value '" + id + "' does not match a valid id pattern.",
						problems);
				return;
			}
		}
	}

	private static void resources(List<PomModel.Resource> resources, String prefix, Severity errOn30,
			ModelProblems problems) {
		for (PomModel.Resource resource : resources) {
			notEmpty(prefix + "directory", Severity.ERROR, resource.directory(), problems);
			bool(prefix, "filtering", errOn30, resource.filtering(), resource.directory(), problems);
		}
	}

	/** {@code validate20PluginVersion}. */
	private static void pluginVersion(PomModel.Plugin plugin, Severity errOn30, ModelProblems problems) {
		String version = plugin.version();
		if (version == null) {
			// a missing version is the model builder's warning
			return;
		}
		String field = "build.plugins.plugin.version";
		if (!version(EMPTY, field, errOn30, version, plugin.key(), problems)) {
			return;
		}
		if (version.isEmpty() || "RELEASE".equals(version) || "LATEST".equals(version)) {
			violation(errOn30, field, plugin.key(), "must be a valid version but is '" + version + "'.", problems);
		}
	}

	private static void effectiveRepository(PomModel.@Nullable Repo repository, String prefix, Severity errOn31,
			ModelProblems problems) {
		if (repository == null) {
			return;
		}
		bannedCharacters(prefix, "id", errOn31, repository.id(), null, problems);
		if ("local".equals(repository.id())) {
			violation(errOn31, prefix + "id", null,
					"must not be 'local', this identifier is reserved for the local"
							+ " repository, using it for other repositories will corrupt your repository metadata.",
					problems);
		}
		if ("legacy".equals(repository.layout())) {
			violation(Severity.WARNING, prefix + "layout", repository.id(),
					"uses the unsupported value 'legacy', artifact resolution might fail.", problems);
		}
	}

	/** {@code validateModelVersion} against 4.0.0. */
	private static void modelVersion(@Nullable String version, ModelProblems problems) {
		if (version == null || version.isEmpty() || "4.0.0".equals(version)) {
			return;
		}
		int comparison = compareModelVersions("4.0.0", version);
		if (comparison < 0) {
			violation(Severity.FATAL, "modelVersion", null, "of '" + version
					+ "' is newer than the versions supported by this version of Maven: [4.0.0]. Building this project"
					+ " requires a newer version of Maven.", problems);
		}
		else if (comparison > 0) {
			violation(Severity.FATAL, "modelVersion", null, "of '" + version
					+ "' is older than the versions supported by this version of Maven: [4.0.0]. Building this project"
					+ " requires an older version of Maven.", problems);
		}
		else {
			violation(Severity.ERROR, "modelVersion", null, "must be one of [4.0.0] but is '" + version + "'.",
					problems);
		}
	}

	/**
	 * Maven's {@code compareModelVersions}: segment by segment as longs. A segment that
	 * is no number is Maven's {@code NumberFormatException}, which no problem collector
	 * catches: the build fails with it.
	 */
	private static int compareModelVersions(String first, String second) {
		String[] firstSegments = segments(first);
		String[] secondSegments = segments(second);
		for (int i = 0; i < Math.max(firstSegments.length, secondSegments.length); i++) {
			int result = Long.valueOf(i < firstSegments.length ? firstSegments[i] : "0")
				.compareTo(Long.valueOf(i < secondSegments.length ? secondSegments[i] : "0"));
			if (result != 0) {
				return result;
			}
		}
		return 0;
	}

	/** plexus {@code StringUtils.split(s, ".")}: the non-empty pieces, trimmed. */
	private static String[] segments(String version) {
		List<String> segments = new ArrayList<>();
		for (String piece : version.split("\\.")) {
			String trimmed = piece.trim();
			if (!trimmed.isEmpty()) {
				segments.add(trimmed);
			}
		}
		return segments.toArray(new String[0]);
	}

	private static void noExpression(String field, @Nullable String value, ModelProblems problems) {
		if (hasExpression(value)) {
			violation(Severity.WARNING, field, null, "contains an expression but should be a constant.", problems);
		}
	}

	/**
	 * {@code validateVersionNoExpression}: only the CI-friendly properties may appear.
	 */
	private static void versionNoExpression(@Nullable String version, ModelProblems problems) {
		if (version == null || !hasExpression(version)) {
			return;
		}
		Matcher matcher = CI_FRIENDLY_EXPRESSION.matcher(version.trim());
		while (matcher.find()) {
			if (!CI_FRIENDLY_PROPERTIES.contains(matcher.group(1))) {
				violation(Severity.WARNING, "version", null, "contains an expression but should be a constant.",
						problems);
				return;
			}
		}
	}

	private static boolean id(String prefix, String field, Severity severity, @Nullable String id,
			@Nullable String hint, ModelProblems problems) {
		if (id == null || id.isEmpty()) {
			violation(severity, prefix + field, hint, "is missing.", problems);
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			if (!isIdCharacter(id.charAt(i))) {
				violation(severity, prefix + field, hint, "with value '" + id + "' does not match a valid id pattern.",
						problems);
				return false;
			}
		}
		return true;
	}

	private static boolean isIdCharacter(char c) {
		return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-' || c == '_' || c == '.';
	}

	private static void notEmpty(String field, Severity severity, @Nullable String value, ModelProblems problems) {
		if (value == null || value.isEmpty()) {
			violation(severity, field, null, "is missing.", problems);
		}
	}

	private static void bool(String prefix, String field, Severity severity, @Nullable String value,
			@Nullable String hint, ModelProblems problems) {
		if (value == null || value.isEmpty() || "true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
			return;
		}
		violation(severity, prefix + field, hint, "must be 'true' or 'false' but is '" + value + "'.", problems);
	}

	private static void oneOf(String prefix, String field, Severity severity, @Nullable String value,
			@Nullable String hint, ModelProblems problems, String... valid) {
		if (value == null || value.isEmpty() || List.of(valid).contains(value)) {
			return;
		}
		violation(severity, prefix + field, hint, "must be one of " + List.of(valid) + " but is '" + value + "'.",
				problems);
	}

	/** {@code validateVersion}: no expression left, no character a file name refuses. */
	private static boolean version(String prefix, String field, Severity severity, @Nullable String version,
			@Nullable String hint, ModelProblems problems) {
		if (version == null || version.isEmpty()) {
			return true;
		}
		if (hasExpression(version)) {
			violation(severity, prefix + field, hint, "must be a valid version but is '" + version + "'.", problems);
			return false;
		}
		return bannedCharacters(prefix, field, severity, version, hint, problems);
	}

	/** {@code validateBannedCharacters}: the last banned character is the one named. */
	private static boolean bannedCharacters(String prefix, String field, Severity severity, @Nullable String value,
			@Nullable String hint, ModelProblems problems) {
		if (value == null) {
			return true;
		}
		for (int i = value.length() - 1; i >= 0; i--) {
			if (ILLEGAL_CHARS.indexOf(value.charAt(i)) >= 0) {
				violation(severity, prefix + field, hint,
						"must not contain any of these characters " + ILLEGAL_CHARS + " but found " + value.charAt(i),
						problems);
				return false;
			}
		}
		return true;
	}

	private static boolean hasExpression(@Nullable String value) {
		return value != null && value.contains("${");
	}

	private static String clean(@Nullable String value) {
		return value == null ? EMPTY : value.trim();
	}

	private static String orUnknown(@Nullable String version) {
		return version == null ? "(?)" : version;
	}

	private static Severity severity(int level, int errorThreshold) {
		return level < errorThreshold ? Severity.WARNING : Severity.ERROR;
	}

	/** {@code addViolation}: {@code 'field' [for hint] message}. */
	private static void violation(Severity severity, String field, @Nullable String hint, String message,
			ModelProblems problems) {
		problems.add(severity, "'" + field + "'" + (hint == null ? EMPTY : " for " + hint) + " " + message);
	}

}
