package am.ik.rontolisp.clojure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;

import am.ik.rontolisp.clojure.ClojureDepsEdn.ArgMap;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Coord;
import am.ik.rontolisp.clojure.ClojureDepsEdn.DepsMap;
import am.ik.rontolisp.clojure.ClojureDepsEdn.Lib;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Contribution;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Root;
import am.ik.rontolisp.clojure.ClojureDepsGraph.Selected;
import org.jspecify.annotations.Nullable;

/**
 * A project's {@code deps.edn} maps read and merged with the selected aliases applied,
 * like the oracle's {@code create-basis}: the root map, the user-level map and the
 * project's, the aliases every map defines merged per alias, the selected ones' arguments
 * merged ({@link ClojureDepsEdn#argMap}), the project map tooled by their
 * {@code :replace-deps}/{@code :replace-paths} ({@link ClojureDepsEdn#tool}), then the
 * maps merged. What it puts on the source path is computed on request ({@link #paths},
 * {@link #libraries}).
 */
final class ClojureBasis {

	private final ClojureFiles files;

	private final @Nullable String projectDir;

	private final DepsMap merged;

	private final ArgMap args;

	private final List<String> undeclared;

	private ClojureBasis(ClojureFiles files, @Nullable String projectDir, DepsMap merged, ArgMap args,
			List<String> undeclared) {
		this.files = files;
		this.projectDir = projectDir;
		this.merged = merged;
		this.args = args;
		this.undeclared = undeclared;
	}

	/**
	 * Reads the maps of the project at or above a directory.
	 * @param files where the maps are read from
	 * @param dir the directory the search for a {@code deps.edn} starts in ({@code ""}
	 * for the working directory)
	 * @param walkUp whether the search goes on to the parent directories (an entry
	 * file's), or stops at the directory (the working directory's, like the oracle)
	 * @return the basis
	 */
	static ClojureBasis create(ClojureFiles files, String dir, boolean walkUp) {
		String projectDir = null;
		DepsMap project = null;
		for (String probe = dir; probe != null; probe = walkUp ? files.parent(probe) : null) {
			String depsFile = files.resolve(probe, "deps.edn");
			String deps = files.read(depsFile);
			if (deps != null) {
				projectDir = probe;
				project = ClojureDepsEdn.read(deps, depsFile);
				break;
			}
		}
		List<DepsMap> maps = new ArrayList<>();
		maps.add(ClojureDepsEdn.ROOT);
		String userDir = files.userConfigDir();
		if (userDir != null) {
			String userFile = files.resolve(userDir, "deps.edn");
			String user = files.read(userFile);
			if (user != null) {
				maps.add(ClojureDepsEdn.read(user, userFile));
			}
		}
		if (project != null) {
			maps.add(project);
		}
		List<String> aliases = files.aliases();
		ArgMap args = aliases.isEmpty() ? ArgMap.EMPTY : ClojureDepsEdn.argMap(ClojureDepsEdn.aliasData(maps), aliases);
		List<DepsMap> tooled = new ArrayList<>(maps);
		DepsMap tooledProject = ClojureDepsEdn.tool(project == null ? ClojureDepsEdn.EMPTY : project, args);
		if (project != null) {
			tooled.set(tooled.size() - 1, tooledProject);
		}
		else {
			tooled.add(tooledProject);
		}
		DepsMap merged = ClojureDepsEdn.merge(tooled);
		return new ClojureBasis(files, projectDir, merged, args,
				ClojureDepsEdn.undeclaredAliases(ClojureDepsEdn.merge(maps), aliases));
	}

	/**
	 * The directory holding the project's {@code deps.edn}.
	 * @return the directory, or {@code null} when no {@code deps.edn} was found (the
	 * working directory stands in)
	 */
	@Nullable String projectDir() {
		return this.projectDir;
	}

	/**
	 * The selected aliases' merged arguments.
	 * @return the arguments
	 */
	ArgMap args() {
		return this.args;
	}

	/**
	 * The selected aliases no map defines.
	 * @return the aliases, in the order selected
	 */
	List<String> undeclared() {
		return this.undeclared;
	}

	/**
	 * The project's own source directories, {@code :extra-paths} ahead of {@code :paths},
	 * resolved against the project's directory.
	 * @return the directories
	 */
	List<String> paths() {
		List<String> out = new ArrayList<>();
		for (String path : ClojureDepsEdn.flattenPaths(this.merged, this.args.get(":extra-paths"))) {
			out.add(this.files.resolve(this.projectDir, path));
		}
		return out;
	}

	/**
	 * The directories the selected aliases' {@code :extra-paths} name, resolved against
	 * the project's directory: what a test alias adds to the source path.
	 * @return the directories
	 */
	List<String> extraPaths() {
		List<String> out = new ArrayList<>();
		for (String path : ClojureDepsEdn.flattenExtraPaths(this.merged, this.args.get(":extra-paths"))) {
			out.add(this.files.resolve(this.projectDir, path));
		}
		return out;
	}

	/**
	 * The libraries the merged {@code :deps} and the aliases' {@code :extra-deps} select,
	 * under the aliases' {@code :override-deps} and {@code :default-deps}, in source path
	 * order, each library a {@code :classpath-overrides} entry names given that path
	 * instead (a blank one drops the library; what it brought in stays, measured on
	 * {@code clj} 1.12.6, 2026-10-08).
	 * @return the libraries
	 */
	List<Selected> libraries() {
		SequencedMap<Lib, @Nullable Coord> deps = new LinkedHashMap<>();
		if (this.merged.deps() != null) {
			deps.putAll(this.merged.deps());
		}
		SequencedMap<Lib, @Nullable Coord> extra = this.args.libs(":extra-deps");
		if (extra != null) {
			deps.putAll(extra);
		}
		if (deps.isEmpty()) {
			return List.of();
		}
		Map<Lib, @Nullable Coord> overrides = orEmpty(this.args.libs(":override-deps"));
		Map<Lib, @Nullable Coord> defaults = orEmpty(this.args.libs(":default-deps"));
		List<Selected> selected = ClojureDepsGraph.resolve(deps, this.projectDir, new ClojureDepsProcurer(this.files),
				overrides, defaults);
		Map<Lib, String> classpathOverrides = this.args.classpathOverrides();
		if (classpathOverrides.isEmpty()) {
			return selected;
		}
		List<Selected> out = new ArrayList<>();
		for (Selected lib : selected) {
			String path = classpathOverrides.get(lib.lib());
			if (path == null) {
				out.add(lib);
			}
			else if (!path.isBlank()) {
				Root root = new Root(this.files.resolve(this.projectDir, path), path.endsWith(".jar"));
				out.add(new Selected(lib.lib(), lib.coord(), new Contribution(List.of(root), false, null)));
			}
		}
		return out;
	}

	private static Map<Lib, @Nullable Coord> orEmpty(@Nullable Map<Lib, @Nullable Coord> map) {
		return map == null ? Map.of() : map;
	}

}
