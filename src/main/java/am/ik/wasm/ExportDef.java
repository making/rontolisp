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
		return this.add(export -> export.write(exportName.length(), exportName, //
				externalKind, signatureIndex));
	}

}
