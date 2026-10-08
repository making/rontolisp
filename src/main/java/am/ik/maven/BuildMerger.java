package am.ik.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import am.ik.maven.PomModel.Build;
import am.ik.maven.PomModel.Execution;
import am.ik.maven.PomModel.Plugin;
import am.ik.maven.PomModel.Resource;
import org.jspecify.annotations.Nullable;

/**
 * How Maven 3.9's model builder merges {@code build} sections, ported from its model
 * mergers ({@code ModelMerger}, {@code MavenModelMerger} and the subclasses each step
 * uses): a parent's into its child ({@code DefaultInheritanceAssembler}), an active
 * profile's into its POM ({@code DefaultProfileInjector}), the plugin management into the
 * plugins ({@code DefaultPluginManagementInjector}) and a POM's duplicate plugins into
 * one ({@code DefaultModelNormalizer}). Plugins match by {@code groupId:artifactId},
 * executions by id; configurations merge as {@link ConfigurationNode#merge}. The orders
 * are Maven's: a child's plugin that precedes one it shares with its parent stays before
 * it, and a merged execution keeps the place of the one it merged into.
 */
final class BuildMerger {

	/** Which merger's rule merges two plugins' executions. */
	private enum Executions {

		/** {@code MavenModelMerger}: the source's inherited ones, then the target's. */
		MAVEN,

		/** {@code ProfileModelMerger}: the target's, then the profile's. */
		PROFILE,

		/** {@code ManagementModelMerger}: the managed ones, then the plugin's. */
		MANAGEMENT

	}

	private BuildMerger() {
	}

	/**
	 * A parent's build (its own chain already merged in) merged into its child's,
	 * {@code InheritanceModelMerger.mergeBuild} with the child dominant: a field the
	 * child leaves out is the parent's, resources the parent's only when the child has
	 * none, plugins and managed plugins merged by key.
	 * @param child the child's build, or {@code null}
	 * @param parent the parent's build, or {@code null}
	 * @return the merged build
	 */
	static @Nullable Build inherit(@Nullable Build child, @Nullable Build parent) {
		if (parent == null) {
			return child;
		}
		Build target = child == null ? Build.EMPTY : child;
		List<Plugin> management = target.pluginManagement();
		List<Plugin> parentManagement = parent.pluginManagement();
		if (parentManagement != null) {
			management = inheritPlugins(management == null ? List.of() : management, parentManagement);
		}
		return new Build(or(target.sourceDirectory(), parent.sourceDirectory()),
				or(target.scriptSourceDirectory(), parent.scriptSourceDirectory()),
				or(target.testSourceDirectory(), parent.testSourceDirectory()),
				or(target.outputDirectory(), parent.outputDirectory()),
				or(target.testOutputDirectory(), parent.testOutputDirectory()),
				or(target.directory(), parent.directory()), or(target.finalName(), parent.finalName()),
				or(target.defaultGoal(), parent.defaultGoal()),
				target.resources().isEmpty() ? parent.resources() : target.resources(),
				target.testResources().isEmpty() ? parent.testResources() : target.testResources(),
				union(target.filters(), parent.filters()), inheritPlugins(target.plugins(), parent.plugins()),
				management);
	}

	/**
	 * An active profile's build merged into its POM's, {@code ProfileModelMerger} with
	 * the profile dominant: its fields win, its resources follow the POM's, its plugins
	 * merge into the POM's by key.
	 * @param model the POM's build, or {@code null}
	 * @param profile the profile's build
	 * @return the merged build
	 */
	static Build injectProfile(@Nullable Build model, Build profile) {
		Build target = model == null ? Build.EMPTY : model;
		List<Plugin> management = target.pluginManagement();
		List<Plugin> profileManagement = profile.pluginManagement();
		if (profileManagement != null) {
			management = injectPlugins(management == null ? List.of() : management, profileManagement);
		}
		return new Build(target.sourceDirectory(), target.scriptSourceDirectory(), target.testSourceDirectory(),
				target.outputDirectory(), target.testOutputDirectory(), or(profile.directory(), target.directory()),
				or(profile.finalName(), target.finalName()), or(profile.defaultGoal(), target.defaultGoal()),
				concat(target.resources(), profile.resources()),
				concat(target.testResources(), profile.testResources()), union(target.filters(), profile.filters()),
				injectPlugins(target.plugins(), profile.plugins()), management);
	}

	/**
	 * The plugin management merged into the plugins it manages,
	 * {@code DefaultPluginManagementInjector}: each plugin dominant over its managed
	 * entry, the managed executions first.
	 * @param build the build, or {@code null}
	 * @return the build, its plugins managed
	 */
	static @Nullable Build injectManagement(@Nullable Build build) {
		if (build == null || build.pluginManagement() == null || build.pluginManagement().isEmpty()) {
			return build;
		}
		Map<String, Plugin> managed = new LinkedHashMap<>();
		for (Plugin plugin : build.pluginManagement()) {
			managed.put(plugin.key(), plugin);
		}
		List<Plugin> plugins = new ArrayList<>();
		for (Plugin plugin : build.plugins()) {
			Plugin entry = managed.get(plugin.key());
			plugins.add(entry == null ? plugin : mergePlugin(plugin, entry, false, Executions.MANAGEMENT));
		}
		return build.withPlugins(plugins, build.pluginManagement());
	}

	/**
	 * A POM's plugins declared twice merged into one, {@code DefaultModelNormalizer}: at
	 * the first one's place, the later one dominant.
	 * @param build the build, or {@code null}
	 * @return the build, each plugin key once
	 */
	static @Nullable Build mergeDuplicates(@Nullable Build build) {
		if (build == null) {
			return null;
		}
		Map<String, Plugin> normalized = new LinkedHashMap<>();
		for (Plugin plugin : build.plugins()) {
			Plugin first = normalized.get(plugin.key());
			normalized.put(plugin.key(), first == null ? plugin : mergePlugin(plugin, first, false, Executions.MAVEN));
		}
		if (normalized.size() == build.plugins().size()) {
			return build;
		}
		return build.withPlugins(new ArrayList<>(normalized.values()), build.pluginManagement());
	}

	/**
	 * {@code InheritanceModelMerger.mergePluginContainer_Plugins}: the parent's plugins a
	 * child inherits (each one {@code inherited}, or with executions), the child's merged
	 * into them, a child's plugin before the shared one it preceded, the child's others
	 * last.
	 */
	private static List<Plugin> inheritPlugins(List<Plugin> child, List<Plugin> parent) {
		if (parent.isEmpty()) {
			return child;
		}
		Map<String, Plugin> master = new LinkedHashMap<>();
		for (Plugin plugin : parent) {
			if (plugin.isInherited() || !plugin.executions().isEmpty()) {
				master.put(plugin.key(), inheritPlugin(Plugin.BLANK, plugin));
			}
		}
		return ordered(master, child, (target, existing) -> inheritPlugin(target, existing));
	}

	/**
	 * {@code ProfileModelMerger.mergePluginContainer_Plugins}: the POM's plugins, each
	 * the profile's of its key merged in (the profile dominant), the profile's others
	 * placed as {@link #inheritPlugins} places a child's.
	 */
	private static List<Plugin> injectPlugins(List<Plugin> model, List<Plugin> profile) {
		if (profile.isEmpty()) {
			return model;
		}
		Map<String, Plugin> master = new LinkedHashMap<>();
		for (Plugin plugin : model) {
			master.put(plugin.key(), plugin);
		}
		return ordered(master, profile, (source, existing) -> mergePlugin(existing, source, true, Executions.PROFILE));
	}

	/** Merges one plugin of the second list into the master's of its key. */
	@FunctionalInterface
	private interface PluginMerge {

		Plugin merge(Plugin incoming, Plugin existing);

	}

	/**
	 * The master's plugins in order, each one of the incoming list shares merged into it,
	 * preceded by the incoming plugins that came before it; the incoming plugins after
	 * the last shared one at the end.
	 */
	private static List<Plugin> ordered(Map<String, Plugin> master, List<Plugin> incoming, PluginMerge merge) {
		Map<String, List<Plugin>> predecessors = new LinkedHashMap<>();
		List<Plugin> pending = new ArrayList<>();
		for (Plugin plugin : incoming) {
			Plugin existing = master.get(plugin.key());
			if (existing != null) {
				master.put(plugin.key(), merge.merge(plugin, existing));
				if (!pending.isEmpty()) {
					predecessors.put(plugin.key(), pending);
					pending = new ArrayList<>();
				}
			}
			else {
				pending.add(plugin);
			}
		}
		List<Plugin> result = new ArrayList<>();
		for (Map.Entry<String, Plugin> entry : master.entrySet()) {
			List<Plugin> before = predecessors.get(entry.getKey());
			if (before != null) {
				result.addAll(before);
			}
			result.add(entry.getValue());
		}
		result.addAll(pending);
		return result;
	}

	/**
	 * {@code InheritanceModelMerger.mergePlugin}, the target dominant: the source's
	 * {@code inherited} and configuration only when it is inherited; its inherited
	 * executions.
	 */
	private static Plugin inheritPlugin(Plugin target, Plugin source) {
		String inherited = target.inherited();
		ConfigurationNode configuration = target.configuration();
		if (source.isInherited()) {
			inherited = or(target.inherited(), source.inherited());
			configuration = mergeConfiguration(target.configuration(), source.configuration(), false);
		}
		return new Plugin(or(target.groupId(), source.groupId()), or(target.artifactId(), source.artifactId()),
				or(target.version(), source.version()), inherited, configuration,
				executions(target, source, false, Executions.MAVEN), or(target.extensions(), source.extensions()),
				PomModel.mergeByKey(target.dependencies(), source.dependencies(), false));
	}

	/**
	 * {@code ModelMerger.mergePlugin}, with the executions merged by the given rule; the
	 * dependencies by management key.
	 */
	private static Plugin mergePlugin(Plugin target, Plugin source, boolean sourceDominant, Executions rule) {
		return new Plugin(pick(target.groupId(), source.groupId(), sourceDominant),
				pick(target.artifactId(), source.artifactId(), sourceDominant),
				pick(target.version(), source.version(), sourceDominant),
				pick(target.inherited(), source.inherited(), sourceDominant),
				mergeConfiguration(target.configuration(), source.configuration(), sourceDominant),
				executions(target, source, sourceDominant, rule),
				pick(target.extensions(), source.extensions(), sourceDominant),
				PomModel.mergeByKey(target.dependencies(), source.dependencies(), sourceDominant));
	}

	private static List<Execution> executions(Plugin target, Plugin source, boolean sourceDominant, Executions rule) {
		if (source.executions().isEmpty()) {
			return target.executions();
		}
		Map<@Nullable String, Execution> merged = new LinkedHashMap<>();
		switch (rule) {
			case MAVEN -> {
				for (Execution execution : source.executions()) {
					String inherited = execution.inherited();
					if (sourceDominant
							|| (inherited != null ? Boolean.parseBoolean(inherited) : source.isInherited())) {
						merged.put(execution.id(), execution);
					}
				}
				putTarget(merged, target.executions(), sourceDominant);
			}
			case MANAGEMENT -> {
				for (Execution execution : source.executions()) {
					merged.put(execution.id(), execution);
				}
				putTarget(merged, target.executions(), sourceDominant);
			}
			case PROFILE -> {
				for (Execution execution : target.executions()) {
					merged.put(execution.id(), execution);
				}
				for (Execution execution : source.executions()) {
					Execution existing = merged.get(execution.id());
					merged.put(execution.id(),
							existing == null ? execution : mergeExecution(existing, execution, sourceDominant));
				}
			}
		}
		return new ArrayList<>(merged.values());
	}

	/** Each target execution merged into the one of its id there, or added. */
	private static void putTarget(Map<@Nullable String, Execution> merged, List<Execution> target,
			boolean sourceDominant) {
		for (Execution execution : target) {
			Execution existing = merged.get(execution.id());
			merged.put(execution.id(),
					existing == null ? execution : mergeExecution(execution, existing, sourceDominant));
		}
	}

	/**
	 * {@code ModelMerger.mergePluginExecution}: {@code inherited}, configuration, id and
	 * phase by dominance, the goals the target's then the source's it lacks.
	 */
	private static Execution mergeExecution(Execution target, Execution source, boolean sourceDominant) {
		List<String> goals = target.goals();
		if (!source.goals().isEmpty()) {
			goals = union(target.goals(), source.goals());
		}
		return new Execution(pick(target.id(), source.id(), sourceDominant),
				pick(target.phase(), source.phase(), sourceDominant), goals,
				pick(target.inherited(), source.inherited(), sourceDominant),
				mergeConfiguration(target.configuration(), source.configuration(), sourceDominant));
	}

	/**
	 * {@code ModelMerger.mergeConfigurationContainer_Configuration}: the dominant tree
	 * over the recessive one.
	 */
	private static @Nullable ConfigurationNode mergeConfiguration(@Nullable ConfigurationNode target,
			@Nullable ConfigurationNode source, boolean sourceDominant) {
		if (source == null) {
			return target;
		}
		if (sourceDominant || target == null) {
			return ConfigurationNode.merge(source, target);
		}
		return ConfigurationNode.merge(target, source);
	}

	/**
	 * A merged field: the source's when it has one and dominates or the target has none.
	 */
	private static @Nullable String pick(@Nullable String target, @Nullable String source, boolean sourceDominant) {
		return source != null && (sourceDominant || target == null) ? source : target;
	}

	private static @Nullable String or(@Nullable String first, @Nullable String second) {
		return first != null ? first : second;
	}

	private static List<Resource> concat(List<Resource> first, List<Resource> second) {
		if (second.isEmpty()) {
			return first;
		}
		List<Resource> result = new ArrayList<>(first);
		result.addAll(second);
		return result;
	}

	/** The first list, then what the second adds to it, each value once. */
	private static List<String> union(List<String> first, List<String> second) {
		if (second.isEmpty()) {
			return first;
		}
		Set<String> merged = new LinkedHashSet<>(first);
		List<String> result = new ArrayList<>(first);
		for (String value : second) {
			if (!merged.contains(value)) {
				result.add(value);
			}
		}
		return result;
	}

}
