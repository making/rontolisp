package am.ik.wasm;

/**
 * Definition for data segments in the WASM data section.
 */
public class DataDef extends CountingDef<DataDef> {

	/** Creates a new empty data definition. */
	public DataDef() {
	}

	/**
	 * Adds an active data segment that initializes linear memory at the given offset.
	 * @param memoryIndex the memory index (typically 0)
	 * @param offset the byte offset in linear memory
	 * @param data the data bytes to write
	 * @return this instance for chaining
	 */
	public DataDef addActiveData(int memoryIndex, int offset, byte[] data) {
		return this.add(w -> {
			w.write(0x00); // flags: active, memory 0
			w.write(Instruction.I32_CONST);
			w.writeSignedLeb128(offset);
			w.write(Instruction.END);
			w.writeUnsignedLeb128(data.length);
			w.write((Object) data);
		});
	}

}
