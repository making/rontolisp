package am.ik.maven;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/**
 * The {@code <versioning>} of a {@code maven-metadata.xml}, read as Maven's lenient
 * metadata reader reads it (an unknown element skipped, a field written twice or a
 * non-numeric build number rejected): an artifact's versions with its latest and release
 * one, or a snapshot version's deployed builds.
 *
 * @param latest the {@code <latest>} version, or {@code null}
 * @param release the {@code <release>} version, or {@code null}
 * @param versions the {@code <versions>}, in document order
 * @param lastUpdated the {@code <lastUpdated>} timestamp, or {@code null}
 * @param snapshot the {@code <snapshot>}, or {@code null}
 * @param snapshotVersions the {@code <snapshotVersions>}, in document order
 */
record MavenMetadata(@Nullable String latest, @Nullable String release, List<String> versions,
		@Nullable String lastUpdated, @Nullable Snapshot snapshot, List<SnapshotVersion> snapshotVersions) {

	/** No versioning at all: what a missing or unreadable file contributes. */
	static final MavenMetadata EMPTY = new MavenMetadata(null, null, List.of(), null, null, List.of());

	private static final Set<String> METADATA_FIELDS = Set.of("groupId", "artifactId", "version", "versioning",
			"plugins");

	private static final Set<String> VERSIONING_FIELDS = Set.of("latest", "release", "versions", "lastUpdated",
			"snapshot", "snapshotVersions");

	private static final Set<String> SNAPSHOT_FIELDS = Set.of("timestamp", "buildNumber", "localCopy");

	private static final Set<String> SNAPSHOT_VERSION_FIELDS = Set.of("classifier", "extension", "value", "updated");

	/**
	 * A {@code <snapshot>}: the latest deployed build of a snapshot version.
	 *
	 * @param timestamp the {@code <timestamp>} ({@code 20240101.123456}), or {@code null}
	 * @param buildNumber the {@code <buildNumber>}, 0 when absent
	 * @param localCopy the {@code <localCopy>} flag
	 */
	record Snapshot(@Nullable String timestamp, int buildNumber, boolean localCopy) {
	}

	/**
	 * A {@code <snapshotVersion>}: the build one file of a snapshot version resolves to.
	 *
	 * @param classifier the classifier, the empty string for none
	 * @param extension the extension
	 * @param value the version ({@code 1.0-20240101.123456-1})
	 * @param updated the {@code <updated>} timestamp, or {@code null}
	 */
	record SnapshotVersion(String classifier, String extension, @Nullable String value, @Nullable String updated) {
	}

	/**
	 * Copies the lists.
	 * @param latest the latest version
	 * @param release the release version
	 * @param versions the versions
	 * @param lastUpdated the timestamp
	 * @param snapshot the snapshot
	 * @param snapshotVersions the snapshot versions
	 */
	MavenMetadata {
		versions = List.copyOf(versions);
		snapshotVersions = List.copyOf(snapshotVersions);
	}

	/**
	 * Reads a metadata file.
	 * @param bytes the file
	 * @return its versioning, {@link #EMPTY} when it has none
	 * @throws XmlParser.Malformed if Maven's reader would not read it
	 */
	static MavenMetadata read(byte[] bytes) throws XmlParser.Malformed {
		XmlElement root = XmlParser.parse(bytes);
		PomReader.structure(root, METADATA_FIELDS);
		XmlElement versioning = root.child("versioning");
		if (versioning == null) {
			return EMPTY;
		}
		PomReader.structure(versioning, VERSIONING_FIELDS);
		List<String> versions = new ArrayList<>();
		for (XmlElement version : PomReader.items(versioning.child("versions"), "version")) {
			versions.add(text(version));
		}
		Snapshot snapshot = null;
		XmlElement snapshotElement = versioning.child("snapshot");
		if (snapshotElement != null) {
			PomReader.structure(snapshotElement, SNAPSHOT_FIELDS);
			String buildNumber = PomReader.leaf(snapshotElement, "buildNumber");
			int number = 0;
			if (buildNumber != null) {
				try {
					number = Integer.parseInt(buildNumber);
				}
				catch (NumberFormatException ex) {
					throw new XmlParser.Malformed("Unable to parse element value, must be an integer: buildNumber '"
							+ buildNumber + "' (line " + snapshotElement.line() + ")");
				}
			}
			snapshot = new Snapshot(PomReader.leaf(snapshotElement, "timestamp"), number,
					"true".equals(PomReader.leaf(snapshotElement, "localCopy")));
		}
		List<SnapshotVersion> snapshotVersions = new ArrayList<>();
		for (XmlElement entry : PomReader.items(versioning.child("snapshotVersions"), "snapshotVersion")) {
			PomReader.structure(entry, SNAPSHOT_VERSION_FIELDS);
			String classifier = PomReader.leaf(entry, "classifier");
			String extension = PomReader.leaf(entry, "extension");
			snapshotVersions
				.add(new SnapshotVersion(classifier == null ? "" : classifier, extension == null ? "" : extension,
						PomReader.leaf(entry, "value"), PomReader.leaf(entry, "updated")));
		}
		return new MavenMetadata(PomReader.leaf(versioning, "latest"), PomReader.leaf(versioning, "release"), versions,
				PomReader.leaf(versioning, "lastUpdated"), snapshot, snapshotVersions);
	}

	private static String text(XmlElement element) throws XmlParser.Malformed {
		if (!element.children().isEmpty()) {
			throw new XmlParser.Malformed("parser must be on START_TAG or TEXT to read text (<" + element.name()
					+ "> holds <" + element.children().get(0).name() + ">, line " + element.line() + ")");
		}
		return element.text().trim();
	}

}
