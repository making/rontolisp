package am.ik.wasm;

/**
 * Definition for data segments in the WASM data section.
 */
public class DataDef extends CountingDef<DataDef> {

	/**
	 * Adds an active data segment that initializes linear memory at the given offset.
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
