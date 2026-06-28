package am.ik.wasm;

/**
 * Definition for WASM export section entries.
 */
public class ExportDef extends CountingDef<ExportDef> {

	/** Creates a new empty export definition. */
	public ExportDef() {
	}

	/**
	 * Add an export entry.
	 * @param exportName the export name
	 * @param externalKind the kind of export
	 * @param signatureIndex the index of the exported item
	 * @return this instance for chaining
	 */
	public ExportDef addExport(String exportName, ExternalKind externalKind, int signatureIndex) {
		// The name length and the exported index are WASM u32 LEB128 fields; encode them
		// as
		// such so indices >= 128 (e.g. a function index past the first 127) are valid.
		return this.add(export -> export.writeUnsignedLeb128(exportName.length())
			.write(exportName, externalKind)
			.writeUnsignedLeb128(signatureIndex));
	}

}
