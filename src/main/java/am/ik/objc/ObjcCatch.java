package am.ik.objc;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

import org.jspecify.annotations.Nullable;

/**
 * An Objective-C exception raised inside a call stops at the call: every
 * {@code objc_msgSend}, super send and C function the new base makes goes through a
 * CATCHING TRAMPOLINE, a few AArch64 instructions this class writes at run time that
 * forward the call unchanged and hold an {@code @catch (id)} around it (.kb/objc.md, "The
 * new base: exceptions and NSError").
 *
 * <p>
 * Why machine code: the C++ unwinder {@code objc_exception_throw} starts searches the
 * stack for a frame whose personality routine claims the exception, and the frames above
 * a send are the FFM downcall stub and JIT-compiled Java, which have no unwind
 * information -- the search ends there, {@code std::terminate} runs, and the process dies
 * with "Terminating app due to uncaught exception". Nothing may unwind through those
 * frames either. So the catching frame must be native and sit between the stub and the
 * callee, and rontolisp ships no native code: this writes it.
 *
 * <h2>The trampoline</h2>
 *
 * A slot takes the callee's arguments as the callee does -- x0-x7, d0-d7, x8 untouched --
 * and copies the caller's stack-argument area (a size bounded from the call's
 * {@link FunctionDescriptor}) under its own frame, so the callee finds its stack
 * arguments where the convention puts them. The callee's address, that size and the
 * recorder to call are in a DATA cell per slot, so the code is written once per region
 * and never again: the code page is read-execute before its first call and stays so (a
 * page written while another thread runs it would fault, and HotSpot's own W^X state is
 * per thread).
 *
 * <p>
 * Its call site is covered by an LSDA whose one handler catches {@code OBJC_EHTYPE_id}
 * (Objective-C's {@code @catch (id)}) under {@code __objc_personality_v0}: a C++
 * exception of another type goes on unwinding as before. The landing pad takes the
 * exception ({@code objc_begin_catch}), retains it, ends the catch, hands the address to
 * the cell's recorder ({@link #caught}, an upcall that keeps it for this thread) and
 * returns zero. Phase 2 of the unwind runs every frame's cleanups between the throw and
 * the slot, so an exception caught here abandons nothing.
 *
 * <h2>Finding the unwind information</h2>
 *
 * The unwinder learns about the code through
 * {@code __unw_add_find_dynamic_unwind_sections} (macOS 14): a finder, also written here,
 * walks the chain of regions and answers for a code page with that region's
 * {@code .eh_frame} (one CIE, one FDE per slot, all naming one LSDA) and a stand-in
 * {@code mach_header} as the image base -- Apple's {@code unw_set_reg} reads the CPU
 * subtype of the image a new IP lies in, and the older {@code __register_frame} passes
 * none (a crash, measured 2026-09-28).
 *
 * <h2>One chain per process</h2>
 *
 * libunwind keeps a short fixed table of finders, and a JVM may hold several copies of
 * this class (the interpreter's, and one renamed copy per compiled program loaded). So
 * the regions are the PROCESS's: the first is published in a system property, the others
 * chained from it, one finder registered for all, and every copy allocates slots in them
 * under one JVM-wide lock. What stays per copy is the recorder a slot's cell names and
 * the cache of slots it allocated.
 *
 * <p>
 * AArch64 only: on x86_64, or a macOS without the finder API, {@link #open} answers
 * {@code null} and a call goes straight to its callee, where an exception still ends the
 * process.
 */
final class ObjcCatch {

	private static final ValueLayout.OfLong L = ValueLayout.JAVA_LONG;

	private static final ValueLayout.OfInt I = ValueLayout.JAVA_INT;

	private static final VarHandle LONG_AT = L.varHandle();

	private static final int PAGE = 16384;

	/** Slots a region holds; its code page and its data page each fit them. */
	private static final int SLOTS = 120;

	private static final int SLOT_SIZE = 128;

	/** The code page: the finder at 0, then the slots. */
	private static final int FIRST_SLOT = 128;

	// The data page: the stand-in mach_header first (the image base the finder answers).
	private static final int D_NEXT = 32;

	private static final int D_USED = 40;

	private static final int D_EH_FRAME_LENGTH = 48;

	private static final int D_BEGIN_CATCH = 64;

	private static final int D_RETAIN = 72;

	private static final int D_END_CATCH = 80;

	private static final int D_FIRST = 88;

	/** Per slot: the callee, the stack bytes to copy, the recorder. */
	private static final int D_CELLS = 128;

	private static final int CELL_SIZE = 32;

	private static final int D_LSDA = D_CELLS + SLOTS * CELL_SIZE;

	/** 4096: the finder adds it with one shifted immediate. */
	private static final int D_EH_FRAME = 4096;

	/** At most this many regions in the process. */
	private static final int MAX_REGIONS = 16;

	/** A stack-argument copy is bounded in steps of this many bytes. */
	private static final int STACK_STEP = 64;

	/** The widest copy a slot makes. */
	private static final int MAX_STACK = 4032;

	/** Where the process's first region is published, as a hexadecimal address. */
	private static final String REGISTRY = "rontolisp.objc.catch";

	private static final ThreadLocal<long[]> RAISED = ThreadLocal.withInitial(() -> new long[2]);

	private final MethodHandle mmap;

	private final MethodHandle mprotect;

	private final MethodHandle icacheInvalidate;

	private final MethodHandle addFinder;

	private final long personality;

	private final long ehTypeId;

	private final long beginCatch;

	private final long endCatch;

	private final long retain;

	private final Map<String, MemorySegment> entries = new ConcurrentHashMap<>();

	private @Nullable MemorySegment caughtStub;

	private ObjcCatch(MethodHandle mmap, MethodHandle mprotect, MethodHandle icacheInvalidate, MethodHandle addFinder,
			long personality, long ehTypeId, long beginCatch, long endCatch, long retain) {
		this.mmap = mmap;
		this.mprotect = mprotect;
		this.icacheInvalidate = icacheInvalidate;
		this.addFinder = addFinder;
		this.personality = personality;
		this.ehTypeId = ehTypeId;
		this.beginCatch = beginCatch;
		this.endCatch = endCatch;
		this.retain = retain;
	}

	/**
	 * The catcher, or {@code null} where it cannot be built: not AArch64, or a runtime
	 * without one of the symbols. Binds the downcalls; writes nothing until the first
	 * {@link #entry}.
	 * @param objc a lookup that finds libobjc's symbols and libSystem's
	 * @param binder binds a downcall by name and shape (so the binding records the shape)
	 * @return the catcher, or {@code null}
	 */
	static @Nullable ObjcCatch open(SymbolLookup objc, BiFunction<String, FunctionDescriptor, MethodHandle> binder) {
		String arch = System.getProperty("os.arch", "");
		if (!arch.equals("aarch64") && !arch.equals("arm64")) {
			return null;
		}
		for (String name : List.of("mmap", "mprotect", "sys_icache_invalidate",
				"__unw_add_find_dynamic_unwind_sections", "__objc_personality_v0", "OBJC_EHTYPE_id", "objc_begin_catch",
				"objc_end_catch", "objc_retain")) {
			if (objc.find(name).isEmpty()) {
				return null;
			}
		}
		AddressLayout p = ValueLayout.ADDRESS;
		return new ObjcCatch(binder.apply("mmap", FunctionDescriptor.of(p, p, L, I, I, I, L)),
				binder.apply("mprotect", FunctionDescriptor.of(I, p, L, I)),
				binder.apply("sys_icache_invalidate", FunctionDescriptor.ofVoid(p, L)),
				binder.apply("__unw_add_find_dynamic_unwind_sections", FunctionDescriptor.of(I, p)),
				address(objc, "__objc_personality_v0"), address(objc, "OBJC_EHTYPE_id"),
				address(objc, "objc_begin_catch"), address(objc, "objc_end_catch"), address(objc, "objc_retain"));
	}

	private static long address(SymbolLookup lookup, String name) {
		return lookup.find(name).orElseThrow(() -> new ObjcException(name + " is missing")).address();
	}

	/**
	 * The address to call instead of {@code target} for a call of this shape: a slot that
	 * catches, or {@code target} itself when no slot can be had.
	 * @param target the callee
	 * @param descriptor the call's shape (its stack arguments bound the copy)
	 * @return what to call
	 */
	MemorySegment entry(long target, FunctionDescriptor descriptor) {
		long stack = stackBound(descriptor);
		if (stack > MAX_STACK) {
			return MemorySegment.ofAddress(target);
		}
		String key = target + "/" + stack;
		MemorySegment found = this.entries.get(key);
		if (found != null) {
			return found;
		}
		MemorySegment caught = caughtStub();
		// JVM-wide: every copy of this class allocates in the process's regions.
		Properties lock = System.getProperties();
		synchronized (lock) {
			found = this.entries.get(key);
			if (found == null) {
				found = allocate(target, stack, caught, lock);
				this.entries.put(key, found);
			}
			return found;
		}
	}

	/**
	 * An upper bound of the bytes the call's stack arguments take, in
	 * {@link #STACK_STEP}s: every argument as if it went on the stack, at its size
	 * rounded to 8 plus 8 of alignment. The registers take the first ones, so the real
	 * area is smaller; the slot copies this much of the caller's frame, which is mapped.
	 * @param descriptor the call's shape
	 * @return the bound
	 */
	static long stackBound(FunctionDescriptor descriptor) {
		long bytes = 0;
		for (MemoryLayout layout : descriptor.argumentLayouts()) {
			bytes += (layout.byteSize() + 7) / 8 * 8 + 8;
		}
		return (bytes + STACK_STEP - 1) / STACK_STEP * STACK_STEP;
	}

	private MemorySegment allocate(long target, long stack, MemorySegment caught, Properties registry) {
		String published = registry.getProperty(REGISTRY);
		MemorySegment data;
		if (published == null) {
			data = newRegion(0);
			if (data == null || !registerFinder(data)) {
				registry.setProperty(REGISTRY, "0");
				return MemorySegment.ofAddress(target);
			}
			registry.setProperty(REGISTRY, Long.toHexString(data.address()));
		}
		else {
			long first = Long.parseUnsignedLong(published, 16);
			if (first == 0) {
				return MemorySegment.ofAddress(target);
			}
			data = page(first);
		}
		long first = data.address();
		int regions = 1;
		while (data.get(L, D_NEXT) != 0) {
			data = page(data.get(L, D_NEXT));
			regions++;
		}
		if (data.get(L, D_USED) == SLOTS) {
			if (regions == MAX_REGIONS) {
				return MemorySegment.ofAddress(target);
			}
			MemorySegment next = newRegion(first);
			if (next == null) {
				return MemorySegment.ofAddress(target);
			}
			// Published with release semantics: the finder may walk the chain on another
			// thread, and must see the region whole.
			LONG_AT.setRelease(data, (long) D_NEXT, next.address());
			data = next;
		}
		int slot = (int) data.get(L, D_USED);
		long cell = D_CELLS + (long) slot * CELL_SIZE;
		data.set(L, cell, target);
		data.set(L, cell + 8, stack);
		data.set(L, cell + 16, caught.address());
		data.set(L, D_USED, slot + 1);
		return MemorySegment.ofAddress(data.address() - PAGE + FIRST_SLOT + (long) slot * SLOT_SIZE);
	}

	private static MemorySegment page(long address) {
		return MemorySegment.ofAddress(address).reinterpret(PAGE);
	}

	/**
	 * A new region, its code page read-execute; answers its data page, or {@code null}.
	 * @param first the first region's data page, or 0 when this is the first
	 */
	private @Nullable MemorySegment newRegion(long first) {
		MemorySegment region;
		try {
			// PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANON.
			region = (MemorySegment) this.mmap.invokeExact(MemorySegment.NULL, (long) (2 * PAGE), 3, 0x1002, -1, 0L);
		}
		catch (Throwable ex) {
			return null;
		}
		if (region.address() == -1L || region.address() == 0) {
			return null;
		}
		region = region.reinterpret(2L * PAGE);
		MemorySegment code = region.asSlice(0, PAGE);
		MemorySegment data = region.asSlice(PAGE, PAGE);
		write(code, data, first == 0 ? data.address() : first);
		try {
			// PROT_READ | PROT_EXEC, for good: nothing writes the code page again.
			if ((int) this.mprotect.invokeExact(code, (long) PAGE, 5) != 0) {
				return null;
			}
			this.icacheInvalidate.invokeExact(code, (long) PAGE);
		}
		catch (Throwable ex) {
			return null;
		}
		return data;
	}

	private boolean registerFinder(MemorySegment firstData) {
		try {
			return (int) this.addFinder.invokeExact(MemorySegment.ofAddress(firstData.address() - PAGE)) == 0;
		}
		catch (Throwable ex) {
			return false;
		}
	}

	private MemorySegment caughtStub() {
		MemorySegment stub = this.caughtStub;
		if (stub == null) {
			synchronized (this) {
				stub = this.caughtStub;
				if (stub == null) {
					try {
						// A CONSTANT lookup, as MainThread's trampoline: a native image
						// folds it.
						MethodHandle target = MethodHandles.lookup()
							.findStatic(ObjcCatch.class, "caught",
									MethodType.methodType(void.class, MemorySegment.class));
						stub = Linker.nativeLinker()
							.upcallStub(target, FunctionDescriptor.ofVoid(ValueLayout.ADDRESS), Arena.global());
					}
					catch (ReflectiveOperationException ex) {
						throw new ObjcException("the exception recorder cannot be bound", ex);
					}
					this.caughtStub = stub;
				}
			}
		}
		return stub;
	}

	/**
	 * The landing pad's upcall: an exception a slot caught on this thread, retained.
	 * @param exception the thrown object
	 */
	private static void caught(MemorySegment exception) {
		long[] raised = RAISED.get();
		raised[0] = 1;
		raised[1] = exception.address();
	}

	/**
	 * Whether the call this thread just made through a slot raised; clears the record.
	 * @return the retained address of what was thrown (0 for nil), or -1 when nothing was
	 */
	static long takeRaised() {
		long[] raised = RAISED.get();
		if (raised[0] == 0) {
			return -1;
		}
		raised[0] = 0;
		return raised[1];
	}

	// --- the region
	// ---------------------------------------------------------------------

	private void write(MemorySegment code, MemorySegment data, long first) {
		data.set(I, 0, 0xFEEDFACF); // MH_MAGIC_64
		data.set(I, 4, 0x0100000C); // CPU_TYPE_ARM64
		data.set(I, 8, 0); // CPU_SUBTYPE_ARM64_ALL: nothing to sign
		data.set(L, D_BEGIN_CATCH, this.beginCatch);
		data.set(L, D_RETAIN, this.retain);
		data.set(L, D_END_CATCH, this.endCatch);
		data.set(L, D_FIRST, first);
		Asm asm = new Asm(code, data);
		writeFinder(asm);
		int[] offsets = new int[2];
		for (int slot = 0; slot < SLOTS; slot++) {
			offsets = writeSlot(asm, FIRST_SLOT + slot * SLOT_SIZE, D_CELLS + slot * CELL_SIZE);
		}
		writeLsda(data, offsets[0], offsets[1]);
		data.set(L, D_EH_FRAME_LENGTH, writeEhFrame(code, data));
	}

	/**
	 * {@code int find(uintptr_t pc, unw_dynamic_unwind_sections *out)}: walks the chain
	 * from the first region; for the region whose code page holds {@code pc}, fills
	 * {@code out} and answers 1, else 0.
	 */
	private static void writeFinder(Asm a) {
		a.at(0);
		a.ldrLiteral(9, D_FIRST);
		int loop = a.here();
		a.emit(0); // cbz x9, no
		a.emit(0xD140112A); // sub x10, x9, #4, lsl #12 -- the code page
		a.emit(0xEB0A001F); // cmp x0, x10
		int below = a.here();
		a.emit(0); // b.lo next
		a.emit(0xEB09001F); // cmp x0, x9
		int above = a.here();
		a.emit(0); // b.hs next
		a.emit(0xF9000029); // str x9, [x1] -- dso_base: the stand-in header
		a.emit(0x9140052B); // add x11, x9, #1, lsl #12 -- the eh_frame
		a.emit(0xF900042B); // str x11, [x1, #8]
		a.emit(0xF940192B); // ldr x11, [x9, #48] -- its length
		a.emit(0xF900082B); // str x11, [x1, #16]
		a.emit(0xA901FC3F); // stp xzr, xzr, [x1, #24] -- no compact unwind
		a.emit(0x52800020); // mov w0, #1
		a.emit(0xD65F03C0); // ret
		int next = a.here();
		a.emit(0xF9401129); // ldr x9, [x9, #32] -- the next region
		a.emit(0x14000000 | (((loop - a.here()) / 4) & 0x3FFFFFF)); // b loop
		int no = a.here();
		a.emit(0x52800000); // mov w0, #0
		a.emit(0xD65F03C0); // ret
		a.patchBranch(loop, no, 0xB4000009); // cbz x9
		a.patchBranch(below, next, 0x54000003); // b.lo
		a.patchBranch(above, next, 0x54000002); // b.hs
	}

	/**
	 * One slot; answers the offsets, from the slot's start, of its call instruction and
	 * of its landing pad (the same in every slot).
	 */
	private static int[] writeSlot(Asm a, int start, int cell) {
		a.at(start);
		a.emit(0xA9BE7BFD); // stp x29, x30, [sp, #-32]!
		a.emit(0x910003FD); // mov x29, sp
		a.ldrLiteral(9, cell + 8); // the stack bytes to copy
		a.emit(0xCB2963FF); // sub sp, sp, x9
		a.emit(0x910083AC); // add x12, x29, #32 -- the caller's stack arguments
		a.emit(0x910003ED); // mov x13, sp
		int skip = a.here();
		a.emit(0); // cbz x9, call
		int loop = a.here();
		a.emit(0xA8C12D8A); // ldp x10, x11, [x12], #16
		a.emit(0xA8812DAA); // stp x10, x11, [x13], #16
		a.emit(0xF1004129); // subs x9, x9, #16
		int back = a.here();
		a.emit(0); // b.ne loop
		a.patchBranch(back, loop, 0x54000001);
		a.patchBranch(skip, a.here(), 0xB4000009);
		a.ldrLiteral(16, cell); // the callee
		int call = a.here();
		a.emit(0xD63F0200); // blr x16
		int after = a.here();
		a.emit(0x910003BF); // mov sp, x29
		a.emit(0xA8C27BFD); // ldp x29, x30, [sp], #32
		a.emit(0xD65F03C0); // ret
		int landing = a.here();
		a.ldrLiteral(16, D_BEGIN_CATCH);
		a.emit(0xD63F0200); // blr x16 -- x0: the thrown object
		a.ldrLiteral(16, D_RETAIN);
		a.emit(0xD63F0200);
		a.emit(0xF9000BA0); // str x0, [x29, #16]
		a.ldrLiteral(16, D_END_CATCH);
		a.emit(0xD63F0200);
		a.emit(0xF9400BA0); // ldr x0, [x29, #16]
		a.ldrLiteral(16, cell + 16); // the recorder
		a.emit(0xD63F0200);
		a.emit(0xD2800000); // mov x0, #0
		a.emit(0xD2800001); // mov x1, #0
		a.emit(0x14000000 | (((after - a.here()) / 4) & 0x3FFFFFF)); // b after
		if (a.here() - start > SLOT_SIZE) {
			throw new IllegalStateException("a catching slot outgrew " + SLOT_SIZE + " bytes");
		}
		return new int[] { call - start, landing - start };
	}

	/**
	 * The LSDA every slot's FDE names: one call site (the {@code blr}) whose handler is
	 * the landing pad, for type 1, {@code OBJC_EHTYPE_id}.
	 */
	private void writeLsda(MemorySegment data, int call, int landing) {
		byte[] lsda = new byte[] { (byte) 0xFF, // LPStart: the function's start
				0x00, // TType: absolute pointers
				0, // TType base offset, below
				0x01, // call sites: ULEB128
				4, // call-site table length
				(byte) call, 4, (byte) landing, 1, // start, length, landing pad, action 1
				1, 0 }; // action: type 1, no next
		lsda[2] = (byte) (lsda.length - 3 + 8);
		MemorySegment.copy(lsda, 0, data, ValueLayout.JAVA_BYTE, D_LSDA, lsda.length);
		data.set(L.withByteAlignment(1), D_LSDA + lsda.length, this.ehTypeId);
	}

	/** One CIE and an FDE per slot; answers the section's length. */
	private long writeEhFrame(MemorySegment code, MemorySegment data) {
		Bytes out = new Bytes(data, D_EH_FRAME);
		int cie = out.position();
		out.u32(0); // length, patched
		out.u32(0); // CIE id
		out.u8(1); // version
		out.bytes("zPLR".getBytes(StandardCharsets.US_ASCII));
		out.u8(0);
		out.u8(1); // code alignment
		out.u8(0x78); // data alignment -8
		out.u8(30); // return address: x30
		out.u8(11); // augmentation data length
		out.u8(0x00); // personality: absolute
		out.u64(this.personality);
		out.u8(0x00); // LSDA: absolute
		out.u8(0x00); // FDE addresses: absolute
		out.u8(0x0C); // DW_CFA_def_cfa sp, 0
		out.u8(31);
		out.u8(0);
		out.padTo8();
		out.patchLength(cie);
		for (int slot = 0; slot < SLOTS; slot++) {
			int fde = out.position();
			out.u32(0);
			out.u32(out.position() - cie); // back to the CIE
			out.u64(code.address() + FIRST_SLOT + (long) slot * SLOT_SIZE);
			out.u64(SLOT_SIZE);
			out.u8(8);
			out.u64(data.address() + D_LSDA);
			out.u8(0x44); // advance 4: after stp
			out.u8(0x0E); // DW_CFA_def_cfa_offset 32
			out.u8(32);
			out.u8(0x9E); // x30 at cfa-24
			out.u8(3);
			out.u8(0x9D); // x29 at cfa-32
			out.u8(4);
			out.u8(0x44); // advance 4: after mov x29, sp
			out.u8(0x0D); // DW_CFA_def_cfa_register x29
			out.u8(29);
			out.padTo8();
			out.patchLength(fde);
		}
		out.u32(0); // terminator
		if (D_EH_FRAME + out.position() > PAGE) {
			throw new IllegalStateException("the catching region's eh_frame outgrew its page");
		}
		return out.position();
	}

	/** Little-endian bytes into the data page. */
	private static final class Bytes {

		private static final ValueLayout.OfInt U32 = I.withByteAlignment(1).withOrder(ByteOrder.LITTLE_ENDIAN);

		private static final ValueLayout.OfLong U64 = L.withByteAlignment(1).withOrder(ByteOrder.LITTLE_ENDIAN);

		private final MemorySegment page;

		private final int base;

		private int position;

		Bytes(MemorySegment page, int base) {
			this.page = page;
			this.base = base;
		}

		int position() {
			return this.position;
		}

		void u8(int value) {
			this.page.set(ValueLayout.JAVA_BYTE, this.base + this.position, (byte) value);
			this.position++;
		}

		void u32(int value) {
			this.page.set(U32, this.base + this.position, value);
			this.position += 4;
		}

		void u64(long value) {
			this.page.set(U64, this.base + this.position, value);
			this.position += 8;
		}

		void bytes(byte[] values) {
			for (byte b : values) {
				u8(b);
			}
		}

		void padTo8() {
			while (this.position % 8 != 0) {
				u8(0);
			}
		}

		void patchLength(int start) {
			this.page.set(U32, this.base + start, this.position - start - 4);
		}

	}

	/** AArch64 instructions into the code page; literals are on the data page. */
	private static final class Asm {

		private final MemorySegment code;

		private final MemorySegment data;

		private int position;

		Asm(MemorySegment code, MemorySegment data) {
			this.code = code;
			this.data = data;
		}

		void at(int offset) {
			this.position = offset;
		}

		int here() {
			return this.position;
		}

		void emit(int instruction) {
			this.code.set(I, this.position, instruction);
			this.position += 4;
		}

		/** {@code ldr x<rt>, <data page + offset>}, PC-relative. */
		void ldrLiteral(int rt, int dataOffset) {
			long distance = this.data.address() + dataOffset - (this.code.address() + this.position);
			emit(0x58000000 | (int) (((distance / 4) & 0x7FFFF) << 5) | rt);
		}

		/** A 19-bit conditional or compare branch at {@code from} to {@code to}. */
		void patchBranch(int from, int to, int base) {
			this.code.set(I, from, base | (((to - from) / 4) & 0x7FFFF) << 5);
		}

	}

}
