package am.ik.maven;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import am.ik.maven.PomModel.Ancillary;
import am.ik.maven.PomModel.Distribution;
import am.ik.maven.PomModel.Repo;
import am.ik.maven.PomModel.ReportPlugin;
import org.jspecify.annotations.Nullable;

/**
 * How Maven 3.9's model mergers merge the repositories, the distribution and the
 * reporting plugins (the parts {@link Ancillary} keeps for validation): a parent's into
 * its child ({@code InheritanceModelMerger}, the child dominant) and an active profile's
 * into its POM ({@code ProfileModelMerger}, the profile dominant). Repositories match by
 * id, the dominant side's first ({@code MavenModelMerger}); a deployment repository is
 * taken whole; reporting plugins match by {@code groupId:artifactId}.
 */
final class AncillaryMerger {

	private AncillaryMerger() {
	}

	/**
	 * A parent's (its own chain merged in) into its child's: the parent's reporting
	 * plugins only when inherited, first, each the child's of its key merged over it.
	 * @param child the child's
	 * @param parent the parent's
	 * @return the merged parts
	 */
	static Ancillary inherit(Ancillary child, Ancillary parent) {
		List<ReportPlugin> reporting = child.reporting();
		List<ReportPlugin> parentReporting = parent.reporting();
		if (parentReporting != null) {
			List<ReportPlugin> target = reporting == null ? List.of() : reporting;
			if (parentReporting.isEmpty()) {
				reporting = target;
			}
			else {
				Map<String, ReportPlugin> merged = new LinkedHashMap<>();
				for (ReportPlugin plugin : parentReporting) {
					if (plugin.isInherited()) {
						merged.put(plugin.key(), plugin);
					}
				}
				for (ReportPlugin plugin : target) {
					ReportPlugin existing = merged.get(plugin.key());
					merged.put(plugin.key(), existing == null ? plugin : merge(plugin, existing, false));
				}
				reporting = new ArrayList<>(merged.values());
			}
		}
		return new Ancillary(repositories(child.repositories(), parent.repositories(), false),
				repositories(child.pluginRepositories(), parent.pluginRepositories(), false),
				distribution(child.distribution(), parent.distribution()), reporting);
	}

	/**
	 * An active profile's into its POM's: the profile's reporting plugins merged over the
	 * POM's of their key, the others after.
	 * @param model the POM's
	 * @param profile the profile's
	 * @return the merged parts
	 */
	static Ancillary injectProfile(Ancillary model, Ancillary profile) {
		List<ReportPlugin> reporting = model.reporting();
		List<ReportPlugin> profileReporting = profile.reporting();
		if (profileReporting != null) {
			List<ReportPlugin> target = reporting == null ? List.of() : reporting;
			Map<String, ReportPlugin> merged = new LinkedHashMap<>();
			for (ReportPlugin plugin : target) {
				merged.put(plugin.key(), plugin);
			}
			for (ReportPlugin plugin : profileReporting) {
				ReportPlugin existing = merged.get(plugin.key());
				merged.put(plugin.key(), existing == null ? plugin : merge(existing, plugin, true));
			}
			reporting = new ArrayList<>(merged.values());
		}
		return new Ancillary(repositories(model.repositories(), profile.repositories(), true),
				repositories(model.pluginRepositories(), profile.pluginRepositories(), true),
				distribution(profile.distribution(), model.distribution()), reporting);
	}

	/**
	 * {@code MavenModelMerger.mergeModelBase_Repositories}: when the source has any, the
	 * dominant list's, a later one of an id replacing an earlier one in its place, then
	 * the recessive list's ids the dominant one lacks.
	 */
	private static List<Repo> repositories(List<Repo> target, List<Repo> source, boolean sourceDominant) {
		if (source.isEmpty()) {
			return target;
		}
		List<Repo> dominant = sourceDominant ? source : target;
		List<Repo> recessive = sourceDominant ? target : source;
		Map<@Nullable String, Repo> merged = new LinkedHashMap<>();
		for (Repo repository : dominant) {
			merged.put(repository.id(), repository);
		}
		for (Repo repository : recessive) {
			merged.putIfAbsent(repository.id(), repository);
		}
		return new ArrayList<>(merged.values());
	}

	/** The dominant side's status and deployment repositories where it has them. */
	private static @Nullable Distribution distribution(@Nullable Distribution dominant,
			@Nullable Distribution recessive) {
		if (recessive == null) {
			return dominant;
		}
		if (dominant == null) {
			return recessive;
		}
		return new Distribution(or(dominant.status(), recessive.status()),
				dominant.repository() != null ? dominant.repository() : recessive.repository(),
				dominant.snapshotRepository() != null ? dominant.snapshotRepository() : recessive.snapshotRepository());
	}

	/** {@code ModelMerger.mergeReportPlugin} over the fields kept. */
	private static ReportPlugin merge(ReportPlugin target, ReportPlugin source, boolean sourceDominant) {
		return new ReportPlugin(pick(target.groupId(), source.groupId(), sourceDominant),
				pick(target.artifactId(), source.artifactId(), sourceDominant),
				pick(target.inherited(), source.inherited(), sourceDominant));
	}

	private static @Nullable String pick(@Nullable String target, @Nullable String source, boolean sourceDominant) {
		return source != null && (sourceDominant || target == null) ? source : target;
	}

	private static @Nullable String or(@Nullable String first, @Nullable String second) {
		return first != null ? first : second;
	}

}
