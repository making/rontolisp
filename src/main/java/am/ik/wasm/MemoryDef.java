package am.ik.wasm;

/**
 * Definition for WASM memory section entries.
 */
public class MemoryDef extends CountingDef<MemoryDef> {

	/** Creates a new empty memory definition. */
	public MemoryDef() {
	}

	/**
	 * Add a memory with a minimum size only.
	 * @param initial the minimum number of pages
	 * @return this instance for chaining
	 */
	public MemoryDef addMemory(int initial) {
		return this.add(memory -> memory.write(Limit.MIN, initial));
	}

	/**
	 * Add a memory with minimum and maximum sizes.
	 * @param initial the minimum number of pages
	 * @param maximum the maximum number of pages
	 * @return this instance for chaining
	 */
	public MemoryDef addMemory(int initial, int maximum) {
		return this.add(memory -> memory.write(Limit.MINMAX, initial, maximum));
	}

}
