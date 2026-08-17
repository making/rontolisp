package am.ik.rontolisp.codegen.wasm;

import java.io.ByteArrayOutputStream;

import am.ik.wasm.Instruction;
import am.ik.wasm.Type;
import am.ik.wasm.WasmWriter;

/**
 * Builds the WASM bodies of the file-stream runtime used by the {@code open},
 * {@code close}, {@code write-line}, {@code read-byte}, {@code write-byte} and
 * stream-taking {@code read-line} built-ins (and therefore by the {@code with-open-file}
 * macro).
 *
 * <p>
 * A stream value is the WASI file descriptor returned by {@code path_open}, boxed as an
 * i31 integer (mirroring the JVM backend, where the handle indexes a stream table). Like
 * {@code load}, {@code open} resolves its path through {@link #buildPathDirFdBody()}
 * against the PREOPEN TABLE -- a relative path against the first preopened directory (fd
 * 3), an absolute one against the preopen whose name is its longest prefix -- so the
 * module must run with {@code --dir}. {@code _write_line} writes the string bytes plus a
 * newline straight through {@code fd_write} (fd 1 = stdout when no stream is given), and
 * {@code _close} delegates to {@code fd_close}.
 */
final class WasmIoRuntimeBuilder {

	private WasmIoRuntimeBuilder() {
	}

	/**
	 * The longest preopen name {@code _path_dirfd} will compare against, and the linear
	 * scratch it reserves for one {@code prestat} record (8 bytes) plus that name.
	 * Preview 1 caps a path component at 255 bytes and a preopen name is a host-chosen
	 * directory path, so 512 is generous; a longer one is SKIPPED rather than truncated,
	 * because a truncated name would compare equal to a prefix that is not the directory
	 * it names.
	 */
	private static final int PRESTAT_NAME_MAX = 512;

	private static final int PRESTAT_SCRATCH_BYTES = 8 + PRESTAT_NAME_MAX;

	/**
	 * How many descriptors above fd 2 the preopen walk will look at. Every host lays its
	 * preopens out contiguously from fd 3 and the walk stops at the first
	 * {@code fd_prestat_get} errno anyway, so this is only a bound against a host that
	 * answers success forever.
	 */
	private static final int PREOPEN_SCAN_MAX = 64;

	/**
	 * Builds the _path_dirfd(ptr, len) function body: the directory descriptor the staged
	 * path at {@code [ptr, ptr+len)} must be opened relative to, with the number of
	 * leading bytes that descriptor already accounts for left in the
	 * {@link WasmLispCompiler#PATH_SKIP_ADDR} cell. The front end of EVERY
	 * {@code path_open} call on this backend -- {@code _open}, {@code _probe_file},
	 * {@code _list_directory} and {@code _load} -- so the resolution rule has one
	 * definition rather than four.
	 *
	 * <p>
	 * A RELATIVE path answers fd 3 with skip 0: the first preopened directory, exactly
	 * what every site hard-coded before, so nothing that worked moves. An ABSOLUTE one (a
	 * leading {@code /}) is matched against the preopen NAMES, which is the whole point:
	 * {@code fd_prestat_get} answers a preopened fd's name length and
	 * {@code fd_prestat_dir_name} the name itself, and without them nothing here can
	 * learn that fd 3 IS {@code /tmp} -- so {@code "/tmp/x.txt"} went to
	 * {@code path_open} whole and WASI rejected it, which is why a runtime-computed
	 * absolute path (asdf:system-relative-pathname, a merge against *load-truename*, a
	 * path out of a config file) could not be opened at all.
	 *
	 * <p>
	 * The match is a path-COMPONENT prefix, longest wins: with {@code --dir /} and
	 * {@code --dir /tmp} both preopened, {@code /tmp/x} resolves against {@code /tmp}
	 * rather than {@code /}, and {@code /tmpfoo} matches neither (the byte after the
	 * prefix must be a separator). A trailing slash on the preopen name is stripped
	 * first, so a host spelling {@code /} and one spelling {@code /tmp/} both behave.
	 * When the remainder would be EMPTY -- the path names the preopened directory itself
	 * -- it becomes {@code "."}, written over the path's last staged byte: WASI takes no
	 * empty path, and the staging is scratch the caller pops right after.
	 *
	 * <p>
	 * When NO preopen covers an absolute path the answer is fd 3 with skip 0, i.e. the
	 * call the site would have made anyway, so the failure surfaces as the ordinary
	 * "cannot open" errno each caller already turns into nil -- an errno, never a trap.
	 * @return the function body bytes
	 */
	static byte[] buildPathDirFdBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: PTR=0 (i32), LEN=1 (i32) ; i32 locals: SCR=2, FD=3, NLEN=4, BFD=5,
		// BSKIP=6, T=7, I=8
		w.write(1);
		w.write(7);
		w.write(Type.I32);
		final int PTR = 0, LEN = 1, SCR = 2, FD = 3, NLEN = 4, BFD = 5, BSKIP = 6, T = 7, I = 8;
		final int SLASH = '/', DOT = '.';

		// mem[PATH_SKIP_ADDR] = 0 -- the answer for every path that is not absolute.
		i32(w, WasmLispCompiler.PATH_SKIP_ADDR);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// if (len == 0 || mem8[ptr] != '/') return 3
		getLocal(w, LEN);
		w.write(Instruction.I32_EQZ);
		getLocal(w, PTR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, SLASH);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_OR);
		w.write(Instruction.IF, 0x40);
		i32(w, 3);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);

		// scr = HEAP_PTR, reserved over the walk and popped after it. Under --component
		// the first fd_prestat_get lifts the preopen list through cabi_realloc, which
		// allocates at HEAP_PTR -- an un-advanced scratch would be overwritten by the
		// very names being read into it (the discipline _open uses for its staged path).
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, SCR);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, SCR);
			i32(w, PRESTAT_SCRATCH_BYTES);
			w.write(Instruction.I32_ADD);
		});
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, SCR);
		i32(w, PRESTAT_SCRATCH_BYTES + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// bfd = 3 ; bskip = 0 (no match yet -- any match scores at least 1) ; fd = 3
		i32(w, 3);
		setLocal(w, BFD);
		i32(w, 0);
		setLocal(w, BSKIP);
		i32(w, 3);
		setLocal(w, FD);

		w.write(Instruction.BLOCK, 0x40); // $done
		w.write(Instruction.LOOP, 0x40); // $scan
		// a non-zero fd_prestat_get errno means fd is not preopened: the walk is over
		getLocal(w, FD);
		getLocal(w, SCR);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_PRESTAT_GET);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// nlen = prestat.u.dir.pr_name_len (u32 at scr+4; the tag byte at scr+0 is 0 for
		// a directory, and a directory is the only preopen kind preview1 defines)
		getLocal(w, SCR);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		setLocal(w, NLEN);
		w.write(Instruction.BLOCK, 0x40); // $next
		getLocal(w, NLEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		getLocal(w, NLEN);
		i32(w, PRESTAT_NAME_MAX);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		// fd_prestat_dir_name(fd, scr + 8, nlen)
		getLocal(w, FD);
		getLocal(w, SCR);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		getLocal(w, NLEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_PRESTAT_DIR_NAME);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		// strip trailing slashes: a host may spell the root "/" and a directory "/tmp/"
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, NLEN);
		i32(w, 1);
		w.write(Instruction.I32_LE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		getLocal(w, SCR);
		getLocal(w, NLEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x07);
		i32(w, SLASH);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		getLocal(w, NLEN);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, NLEN);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// a preopen whose own name is relative (wasmtime's `--dir .` spells it ".") can
		// never cover an absolute path
		getLocal(w, SCR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x08);
		i32(w, SLASH);
		w.write(Instruction.I32_NE);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		// t = this preopen's skip, 0 when it does not cover the path
		i32(w, 0);
		setLocal(w, T);
		getLocal(w, NLEN);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// the root preopen "/" covers every absolute path
		i32(w, 1);
		setLocal(w, T);
		w.write(Instruction.ELSE);
		getLocal(w, NLEN);
		getLocal(w, LEN);
		w.write(Instruction.I32_LE_U);
		w.write(Instruction.IF, 0x40);
		// t = memeq(ptr, scr + 8, nlen) -- provisionally 1, cleared on the first
		// differing byte
		i32(w, 0);
		setLocal(w, I);
		i32(w, 1);
		setLocal(w, T);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		getLocal(w, I);
		getLocal(w, NLEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		getLocal(w, PTR);
		getLocal(w, I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		getLocal(w, SCR);
		getLocal(w, I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x08);
		w.write(Instruction.I32_NE);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		setLocal(w, T);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.END); // if (byte differs)
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		getLocal(w, T);
		w.write(Instruction.IF, 0x40);
		getLocal(w, NLEN);
		getLocal(w, LEN);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		// the path names the preopened directory itself
		getLocal(w, NLEN);
		setLocal(w, T);
		w.write(Instruction.ELSE);
		getLocal(w, PTR);
		getLocal(w, NLEN);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, SLASH);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, NLEN);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, T);
		w.write(Instruction.ELSE);
		// a component boundary is required: "/tmpfoo" is not under "/tmp"
		i32(w, 0);
		setLocal(w, T);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END); // if (the name matched)
		w.write(Instruction.END); // if (nlen <= len)
		w.write(Instruction.END); // if (nlen == 1)
		// longest prefix wins
		getLocal(w, T);
		getLocal(w, BSKIP);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.IF, 0x40);
		getLocal(w, T);
		setLocal(w, BSKIP);
		getLocal(w, FD);
		setLocal(w, BFD);
		w.write(Instruction.END);
		w.write(Instruction.END); // $next
		getLocal(w, FD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, FD);
		getLocal(w, FD);
		i32(w, 3 + PREOPEN_SCAN_MAX);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // $scan
		w.write(Instruction.END); // $done

		// pop the prestat scratch
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, SCR);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// nothing covers it: hand the whole path to fd 3, whose path_open answers the
		// ordinary "cannot open" errno the call site already turns into nil
		getLocal(w, BSKIP);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		i32(w, 3);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// an empty remainder is not a path WASI accepts: relative to its own descriptor
		// the preopened directory is ".". The staging is scratch, so the dot goes in
		// place of the path's last byte.
		getLocal(w, BSKIP);
		getLocal(w, LEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.IF, 0x40);
		getLocal(w, PTR);
		getLocal(w, LEN);
		w.write(Instruction.I32_ADD);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		i32(w, DOT);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		setLocal(w, BSKIP);
		w.write(Instruction.END);
		i32(w, WasmLispCompiler.PATH_SKIP_ADDR);
		getLocal(w, BSKIP);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, BFD);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Emits the first four {@code path_open} arguments -- {@code dirfd},
	 * {@code dirflags}, {@code path_ptr}, {@code path_len} -- for a path staged at
	 * {@code off + 1} with length {@code plen} (the {@code _str_to_mem} framing every
	 * caller here uses: the content sits between the quote bytes). The pair comes from
	 * {@link #buildPathDirFdBody()} rather than from a hard-coded fd 3, which is what
	 * makes an absolute runtime path resolvable; a relative one is unchanged.
	 * @param w the writer
	 * @param off the local holding the staged path's base offset
	 * @param plen the local holding the staged path's content length
	 */
	static void emitDirFdAndPath(WasmWriter w, int off, int plen) {
		// dirfd = _path_dirfd(off + 1, plen) -- it also writes PATH_SKIP_ADDR
		getLocal(w, off);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, plen);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PATH_DIRFD);
		// dirflags = 0 (no symlink following, as before)
		i32(w, 0);
		// path_ptr = off + 1 + skip
		getLocal(w, off);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		loadMem32(w, WasmLispCompiler.PATH_SKIP_ADDR);
		w.write(Instruction.I32_ADD);
		// path_len = plen - skip
		getLocal(w, plen);
		loadMem32(w, WasmLispCompiler.PATH_SKIP_ADDR);
		w.write(Instruction.I32_SUB);
	}

	/**
	 * Builds the _open(path, mode) function body. Opens the file named by the path string
	 * via WASI path_open (mode 0 = read, 1 = create/truncate for write) and returns the
	 * file descriptor as an i31 integer, or {@code ref.null eq} (nil) when path_open
	 * failed. The failure is answered rather than trapped so the CALL SITE can signal a
	 * real Lisp error ({@link WasmOpenCompiler}) -- a trap is not catchable, and a
	 * library that probes for an optional file by opening it inside {@code handler-case}
	 * (local-time reading {@code /etc/localtime}) would otherwise abort the whole program
	 * on the one backend that has no filesystem.
	 * @return the function body bytes
	 */
	static byte[] buildOpenBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: PATH=0 (ref), MODE=1 (i32) ; i32 locals: OFF=2, PLEN=3
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		final int PATH = 0, MODE = 1, OFF = 2, PLEN = 3;

		// The path bytes live on the GC heap, so copy them into linear scratch at
		// HEAP_PTR and derive the pointer + length path_open needs. off = HEAP_PTR ;
		// plen = _str_to_mem(path, off) - 2 (strip the surrounding quotes). HEAP_PTR is
		// then ADVANCED over the staged bytes for the duration of the call (and popped
		// back right after): under --component the adapter's first open lifts the
		// preopen directory list through cabi_realloc, which allocates at HEAP_PTR --
		// an un-advanced staging would be overwritten before path_open reads it.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, PATH);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		// HEAP_PTR = align8(off + plen + 2)
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, PLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2 + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// path_open(dirfd, dirflags=0, path_ptr, path_len -- all four from
		// emitDirFdAndPath, which resolves the staged path against the preopen table,
		// oflags=(read: 0, write: CREAT|TRUNC=9, append: CREAT=1),
		// fs_rights_base=(read: FD_READ=2, write: FD_WRITE|FD_SEEK|FD_TELL=100),
		// fs_rights_inheriting=0, fdflags=(append: APPEND=1, else 0),
		// fd_out=OPEN_FD_ADDR).
		// MODE here is WasmOpenCompiler.wasmMode: 0 read / 1 write / 2 append.
		emitDirFdAndPath(w, OFF, PLEN);
		getLocal(w, MODE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 0);
		w.write(Instruction.ELSE);
		// mode 2 = :append -- O_CREAT alone, because O_TRUNC would discard exactly the
		// content the append is there to keep.
		getLocal(w, MODE);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 1);
		w.write(Instruction.ELSE);
		i32(w, 9);
		w.write(Instruction.END);
		w.write(Instruction.END);
		getLocal(w, MODE);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I64);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.ELSE);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(100);
		w.write(Instruction.END);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		// fdflags = FDFLAGS_APPEND (1) for mode 2, 0 otherwise -- the i32.eq result IS
		// the flag value.
		getLocal(w, MODE);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		i32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PATH_OPEN);
		// pop the staged path (PLEN is free now: reuse it for the errno)
		setLocal(w, PLEN);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// if errno != 0: answer nil (the call site turns it into a Lisp error)
		getLocal(w, PLEN);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// return ref.i31(mem[OPEN_FD_ADDR])
		loadMem32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _probe_file(path) function body: the path when it names an existing
	 * file, {@code ref.null eq} (nil) otherwise. Same staging and {@code path_open} call
	 * as {@link #buildOpenBody()} in read mode, with the two differences that make it a
	 * probe rather than an open: a non-zero errno answers nil instead of trapping (a wasm
	 * trap is not catchable, which is exactly why {@code (handler-case (open ...))}
	 * cannot stand in for this on WASM), and a successful open is closed again via
	 * {@code fd_close} so probing leaks no descriptor.
	 * @return the function body bytes
	 */
	static byte[] buildProbeFileBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: PATH=0 (ref) ; i32 locals: OFF=1, PLEN=2
		w.write(1);
		w.write(2);
		w.write(Type.I32);
		final int PATH = 0, OFF = 1, PLEN = 2;

		// Stage the path bytes into linear scratch exactly as _open does (see there for
		// why HEAP_PTR is advanced over the staging for the duration of the call).
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, PATH);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		// HEAP_PTR = align8(off + plen + 2)
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, PLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2 + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// path_open(dirfd/dirflags/path_ptr/path_len from emitDirFdAndPath, oflags=0,
		// fs_rights_base=FD_READ=2, fs_rights_inheriting=0, fdflags=0,
		// fd_out=OPEN_FD_ADDR)
		emitDirFdAndPath(w, OFF, PLEN);
		i32(w, 0);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(2);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		i32(w, 0);
		i32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PATH_OPEN);
		// pop the staged path (PLEN is free now: reuse it for the errno)
		setLocal(w, PLEN);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// if errno != 0: return nil
		getLocal(w, PLEN);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// close the descriptor the probe just opened, then answer with the path itself
		loadMem32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_CLOSE);
		w.write(Instruction.DROP);
		getLocal(w, PATH);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * The listing buffer handed to {@code fd_readdir}, and the scratch that follows it
	 * where each entry's quoted name is assembled before {@code _str_fresh} copies it
	 * onto the GC heap. Both live above HEAP_PTR (transient scratch, never advanced --
	 * nothing between them allocates in linear memory), so the pair costs no permanent
	 * heap. 8 KiB holds ~340 typical dirents per round trip and the loop resumes through
	 * the cookie anyway; the 512-byte tail covers preview1's 255-byte name ceiling plus
	 * the quotes and the directory slash.
	 */
	private static final int READDIR_BUF_BYTES = 8192;

	private static final int READDIR_NAME_SCRATCH_BYTES = 512;

	/**
	 * Builds the _list_directory(path) function body: {@code (t . names)} for a readable
	 * directory, {@code ref.null eq} (nil) otherwise -- the one directory-LISTING
	 * primitive, over which {@code directory} and the {@code uiop:} spellings are Lisp
	 * source (LispPreludeLibrary). The path is staged into linear scratch and opened as a
	 * DIRECTORY through {@code path_open} exactly as {@link #buildProbeFileBody()} opens
	 * a file, then {@code fd_readdir}'s preview1 dirent stream is drained in
	 * {@value #READDIR_BUF_BYTES}-byte rounds, resuming through the cookie of the last
	 * COMPLETE entry (a round can end mid-entry, and the truncated tail must not be
	 * decoded).
	 *
	 * <p>
	 * Two rules keep the answer identical to the other three backends. A failed open -- a
	 * missing path, a plain file, a host with no preopened directory -- answers nil
	 * rather than trapping, like {@code probe-file}, so a library walking an OPTIONAL
	 * tree degrades instead of aborting. And the {@code "."} / {@code ".."} entries a
	 * preview1 host yields are SKIPPED: {@code Files.list} and wasi:filesystem's
	 * {@code read-directory} both omit them, so emitting them here would be the one
	 * backend that walks its own parent forever.
	 * @return the function body bytes
	 */
	static byte[] buildListDirectoryBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: PATH=0 (ref) ; i32 locals 1..9, i64 local 10, ref local 11
		final int PATH = 0, OFF = 1, PLEN = 2, FD = 3, BUF = 4, END = 5, P = 6, NAMLEN = 7, DST = 8, I = 9;
		final int COOKIE = 10, ACC = 11;
		w.write(3);
		w.write(9);
		w.write(Type.I32);
		w.write(1);
		w.write(Type.I64);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());

		// Stage the path bytes into linear scratch exactly as _open / _probe_file do.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, PATH);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, PLEN);
		// HEAP_PTR = align8(off + plen + 2)
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		getLocal(w, PLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 2 + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// path_open(dirfd/dirflags/path_ptr/path_len from emitDirFdAndPath,
		// oflags=DIRECTORY=2, fs_rights_base=FD_READDIR=1<<14, fs_rights_inheriting=0,
		// fdflags=0, fd_out=OPEN_FD_ADDR)
		emitDirFdAndPath(w, OFF, PLEN);
		i32(w, 2);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(1 << 14);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		i32(w, 0);
		i32(w, WasmLispCompiler.OPEN_FD_ADDR);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_PATH_OPEN);
		// pop the staged path (PLEN is free now: reuse it for the errno)
		setLocal(w, PLEN);
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, OFF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// if errno != 0: not a readable directory -> nil
		getLocal(w, PLEN);
		w.write(Instruction.IF, 0x40);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		loadMem32(w, WasmLispCompiler.OPEN_FD_ADDR);
		setLocal(w, FD);
		// buf = HEAP_PTR (transient; the name scratch follows it), grown to cover both.
		// HEAP_PTR is then ADVANCED over the pair for the duration of the walk and popped
		// back at the end -- the same discipline _open uses for its staged path, and here
		// it is load-bearing under --component: every directory-entry NAME the canonical
		// ABI lifts is allocated through cabi_realloc, which bumps this very cell, so an
		// un-advanced buffer would be overwritten by the names being read into it.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, BUF);
		WasmEmitHelper.emitGrowHeapTo(w, () -> {
			getLocal(w, BUF);
			i32(w, READDIR_BUF_BYTES + READDIR_NAME_SCRATCH_BYTES);
			w.write(Instruction.I32_ADD);
		});
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, BUF);
		i32(w, READDIR_BUF_BYTES + READDIR_NAME_SCRATCH_BYTES + 7);
		w.write(Instruction.I32_ADD);
		i32(w, -8);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// acc = nil ; cookie = 0
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		setLocal(w, ACC);
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(0);
		setLocal(w, COOKIE);
		// outer: block { loop { ... } }
		w.write(Instruction.BLOCK);
		w.write(0x40);
		w.write(Instruction.LOOP);
		w.write(0x40);
		// errno = fd_readdir(fd, buf, BUF_BYTES, cookie, READDIR_USED_ADDR)
		getLocal(w, FD);
		getLocal(w, BUF);
		i32(w, READDIR_BUF_BYTES);
		getLocal(w, COOKIE);
		i32(w, WasmLispCompiler.READDIR_USED_ADDR);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READDIR);
		// a non-zero errno ends the walk with whatever was collected (break outer)
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// end = buf + bufused ; nothing written means the directory is exhausted
		getLocal(w, BUF);
		loadMem32(w, WasmLispCompiler.READDIR_USED_ADDR);
		w.write(Instruction.I32_ADD);
		setLocal(w, END);
		getLocal(w, END);
		getLocal(w, BUF);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// p = buf
		getLocal(w, BUF);
		setLocal(w, P);
		// inner: block { loop { ... } } over the dirents in this round
		w.write(Instruction.BLOCK);
		w.write(0x40);
		w.write(Instruction.LOOP);
		w.write(0x40);
		// a 24-byte dirent header must fit, and its name after it
		getLocal(w, P);
		i32(w, 24);
		w.write(Instruction.I32_ADD);
		getLocal(w, END);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// namlen = u32 at p+16 (d_namlen)
		getLocal(w, P);
		i32(w, 16);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, NAMLEN);
		getLocal(w, P);
		i32(w, 24);
		w.write(Instruction.I32_ADD);
		getLocal(w, NAMLEN);
		w.write(Instruction.I32_ADD);
		getLocal(w, END);
		w.write(Instruction.I32_GT_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// cookie = d_next (u64 at p+0): the resume point of the last COMPLETE entry
		getLocal(w, P);
		w.write(Instruction.I64_LOAD, 0x03, 0x00);
		setLocal(w, COOKIE);
		// skip "." and ".." -- see the class comment on why they must not be collected
		emitDotEntryTest(w, P, NAMLEN);
		w.write(Instruction.IF, 0x40);
		// dst = buf + BUF_BYTES ; memory[dst] = '"'
		getLocal(w, BUF);
		i32(w, READDIR_BUF_BYTES);
		w.write(Instruction.I32_ADD);
		setLocal(w, DST);
		getLocal(w, DST);
		i32(w, 0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// copy the name bytes: for (i = 0; i < namlen; i++) dst[1+i] = p[24+i]
		i32(w, 0);
		setLocal(w, I);
		w.write(Instruction.BLOCK);
		w.write(0x40);
		w.write(Instruction.LOOP);
		w.write(0x40);
		getLocal(w, I);
		getLocal(w, NAMLEN);
		w.write(Instruction.I32_GE_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		getLocal(w, DST);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, I);
		w.write(Instruction.I32_ADD);
		getLocal(w, P);
		i32(w, 24);
		w.write(Instruction.I32_ADD);
		getLocal(w, I);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// a directory entry (preview1 filetype 3) carries the trailing '/'
		getLocal(w, P);
		i32(w, 20);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, 3);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, DST);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, NAMLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 0x2f);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, NAMLEN);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, NAMLEN);
		w.write(Instruction.END);
		// memory[dst+1+namlen] = '"' ; acc = cons(_str_fresh(dst, namlen + 2), acc)
		getLocal(w, DST);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		getLocal(w, NAMLEN);
		w.write(Instruction.I32_ADD);
		i32(w, 0x22);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, DST);
		getLocal(w, NAMLEN);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		WasmEmitHelper.emitStrFreshCall(w);
		getLocal(w, ACC);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		setLocal(w, ACC);
		// namlen may have grown by the slash; restore it for the p advance below
		getLocal(w, P);
		i32(w, 16);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, NAMLEN);
		w.write(Instruction.END); // if (not a dot entry)
		// p += 24 + namlen
		getLocal(w, P);
		i32(w, 24);
		w.write(Instruction.I32_ADD);
		getLocal(w, NAMLEN);
		w.write(Instruction.I32_ADD);
		setLocal(w, P);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // inner loop
		w.write(Instruction.END); // inner block
		// Only an EMPTY round ends the walk (the test at the top of the loop). A short
		// one does not: a preview1 host fills the buffer and truncates the last entry,
		// so "used < buflen" reads as "exhausted" there -- but the --component adapter
		// stops at the last record that fits WHOLE, which makes a full directory look
		// short and silently truncated the listing on that backend alone.
		// A round that decoded no complete entry would spin forever: bail out.
		getLocal(w, P);
		getLocal(w, BUF);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // outer loop
		w.write(Instruction.END); // outer block
		// pop the listing buffer, close the descriptor and answer (t . names)
		i32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		getLocal(w, BUF);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, FD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_CLOSE);
		w.write(Instruction.DROP);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_T_SYM);
		getLocal(w, ACC);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CONS);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Pushes 1 when the dirent at {@code pSlot} is NOT the {@code "."} / {@code ".."}
	 * self/parent entry, i.e. when it should be collected.
	 */
	private static void emitDotEntryTest(WasmWriter w, int pSlot, int namlenSlot) {
		// !(namlen <= 2 && name[0] == '.' && (namlen == 1 || name[1] == '.'))
		getLocal(w, namlenSlot);
		i32(w, 2);
		w.write(Instruction.I32_LE_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, pSlot);
		i32(w, 24);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, 0x2e);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, namlenSlot);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 1);
		w.write(Instruction.ELSE);
		getLocal(w, pSlot);
		i32(w, 25);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, 0x2e);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		i32(w, 0);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		i32(w, 0);
		w.write(Instruction.END);
		w.write(Instruction.I32_EQZ);
	}

	/**
	 * Builds the _close(stream) function body. Closes the file descriptor via WASI
	 * fd_close and returns the symbol {@code T}.
	 * @param st the string table (for the {@code T} symbol)
	 * @param ostreamTableGlobal the module global holding the string output-stream buffer
	 * table
	 * @return the function body bytes
	 */
	static byte[] buildCloseBody(WasmLispCompiler.StringTable st, int ostreamTableGlobal) {
		WasmLispCompiler.StringTable.StringEntry t = st.addBodyString("T");
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: FD_VAL=0 (ref) ; i32 locals: FD=1, REC=2, SLOT=3
		w.write(1);
		w.write(3);
		w.write(Type.I32);
		final int FD = 1, REC = 2, SLOT = 3;
		// fd = i31.get_s(fd_val)
		getLocal(w, 0);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		setLocal(w, FD);
		// A negative handle is a string stream: an OUTPUT one hands its buffer table slot
		// back so the bytes become collectable and the slot is reused (the record's own
		// 12 bytes stay -- see WasmStringStreamRuntimeBuilder), an input one is nothing
		// but its record and needs no unwinding.
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, FD);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		WasmStringStreamRuntimeBuilder.emitCloseOutputRecord(w, REC, SLOT, ostreamTableGlobal);
		w.write(Instruction.END);
		// fd_close only for a real USER fd -- the process standard streams (0/1/2, among
		// them the *error-output* designator) outlive a close of them, as they do on the
		// interpreter and the JVM.
		getLocal(w, FD);
		i32(w, (int) am.ik.rontolisp.compiler.StreamDesignators.FIRST_USER_HANDLE);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, FD);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_CLOSE);
		w.write(Instruction.DROP);
		w.write(Instruction.END);
		// return t
		i32(w, t.offset());
		i32(w, t.length());
		WasmEmitHelper.emitStrBuildCall(w);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _write_line(str, stream) function body. Writes the string content
	 * (without the surrounding quotes) plus a newline to the stream's file descriptor (1
	 * = stdout when the stream is nil) via fd_write, and returns the string.
	 * @param st the string table (for the newline byte)
	 * @return the function body bytes
	 */
	static byte[] buildWriteLineBody(WasmLispCompiler.StringTable st) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STR=0 (ref), FD_VAL=1 (ref) ; i32 locals: OFF=2, LEN=3, FD=4, REC=5
		// (the last only for the string-stream branch)
		w.write(1);
		w.write(4);
		w.write(Type.I32);
		final int STR = 0, FD_VAL = 1, OFF = 2, LEN = 3, FD = 4, REC = 5;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;

		// fd = stream is an i31 handle ? i31.get_s(stream) : 1 (stdout) -- nil and the
		// designator t (a redirected *standard-output*'s default) both mean stdout. The
		// dispatch comes FIRST: the string-output-stream branch copies GC array to GC
		// array and must not stage the string into linear memory at all.
		getLocal(w, FD_VAL);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, FD_VAL);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, FD_VAL);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		i32(w, 1);
		w.write(Instruction.END);
		w.write(Instruction.ELSE);
		i32(w, 1);
		w.write(Instruction.END);
		setLocal(w, FD);
		// A negative handle is a string output stream: append the content and a newline
		// to its byte buffer (see WasmStringStreamRuntimeBuilder) and return the string.
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, FD);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		getLocal(w, STR);
		WasmEmitHelper.emitStrBytesArray(w);
		w.write(Instruction.GC_PREFIX, Instruction.ARRAY_LEN);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		setLocal(w, LEN);
		WasmStringStreamRuntimeBuilder.emitAppend(w, REC, LEN, () -> {
			getLocal(w, STR);
			WasmEmitHelper.emitStrBytesArray(w);
		}, () -> i32(w, 1));
		WasmStringStreamRuntimeBuilder.emitAppendByte(w, REC, 10);
		getLocal(w, STR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// The string's bytes live on the GC heap: copy them into linear scratch at
		// HEAP_PTR so OFF/LEN name a real linear range (off = base, len = total incl.
		// quotes). HEAP_PTR is NOT advanced -- the fd_write below consumes the copy
		// immediately.
		loadMem32(w, WasmLispCompiler.HEAP_PTR_ADDR);
		setLocal(w, OFF);
		getLocal(w, STR);
		getLocal(w, OFF);
		WasmEmitHelper.emitStrToMemCall(w);
		setLocal(w, LEN);
		// iov.ptr = off + 1 ; iov.len = len - 2 (strip surrounding quotes)
		i32(w, IOV);
		getLocal(w, OFF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		getLocal(w, LEN);
		i32(w, 2);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_write(fd, IOV, 1, NWRITTEN)
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		// iov.ptr = newline ; iov.len = 1
		i32(w, IOV);
		i32(w, st.newline.offset());
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, st.newline.length());
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_write(fd, IOV, 1, NWRITTEN)
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		// return the string
		getLocal(w, STR);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _read_byte(stream, eof-error-p, eof-value) function body. Reads one raw
	 * byte from the stream's file descriptor via fd_read into the BYTE_SCRATCH_ADDR
	 * scratch cell -- no quote framing, no newline scan -- and returns it as an i31
	 * integer. On EOF returns eof-value when eof-error-p is nil, otherwise traps.
	 *
	 * <p>
	 * A non-handle designator -- nil, or the {@code t} an unbound
	 * {@code *standard-input*} reads as -- is fd 0, the process standard input, exactly
	 * like {@code _read_char}'s dispatch: the test is "is a handle", not "is nil",
	 * because a {@code ref.cast} on the {@code t} struct would trap.
	 * @return the function body bytes
	 */
	static byte[] buildReadByteBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STREAM=0 (ref), EOF_ERROR_P=1 (ref), EOF_VALUE=2 (ref) ; i32 local:
		// FD=3
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		final int STREAM = 0, EOF_ERROR_P = 1, EOF_VALUE = 2, FD = 3;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		final int SCRATCH = WasmLispCompiler.BYTE_SCRATCH_ADDR;

		// fd = stream is an i31 handle ? i31.get_s(stream) : 0 (stdin)
		getLocal(w, STREAM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		i32(w, 0);
		w.write(Instruction.END);
		setLocal(w, FD);
		// iov.ptr = BYTE_SCRATCH_ADDR ; iov.len = 1
		i32(w, IOV);
		i32(w, SCRATCH);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_read(fd, IOV, 1, NWRITTEN) ; drop errno
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		// if (mem[NWRITTEN] == 0): EOF -- return eof-value when eof-error-p is nil,
		// trap otherwise
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, EOF_ERROR_P);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, EOF_VALUE);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
		w.write(Instruction.END);
		// return ref.i31(mem_u8[BYTE_SCRATCH_ADDR])
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		w.write(Instruction.GC_PREFIX, Instruction.I31_REF_NEW);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _read_char(stream, eof-error-p, eof-value) function body. Reads one
	 * Unicode CODE POINT (1..4 UTF-8 bytes) from the stream and returns it as a character
	 * struct -- WASM strings are UTF-8 encoded on the byte model but a CHARACTER is a
	 * code point on every backend, so read-char decodes the sequence starting at the
	 * cursor. A nil stream reads from standard input (fd 0); a negative i31 handle is a
	 * string input stream whose {@code [kind][cursor][end]} record is consumed 1..4 bytes
	 * at a time (the sequence is clamped against the buffer end -- a truncated tail
	 * yields the lead byte as a bare CHARACTER); a non-negative handle is a WASI fd read
	 * via {@code fd_read} where the lead byte's continuation count drives per-byte
	 * follow-up reads into the {@code BYTE_SCRATCH_ADDR} scratch cell (a truncated tail
	 * from EOF mid-sequence also falls back to the lead byte). On EOF at the start
	 * returns eof-value when eof-error-p is nil, otherwise traps.
	 * @return the function body bytes
	 */
	static byte[] buildReadCharBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STREAM=0 (ref), EOF_ERROR_P=1 (ref), EOF_VALUE=2 (ref) ; i32 locals:
		// FD=3, REC=4, CUR=5, END=6, NEEDED=7, B0=8, B1=9, B2=10, B3=11, I=12
		w.write(1);
		w.write(10);
		w.write(Type.I32);
		final int STREAM = 0, EOF_ERROR_P = 1, EOF_VALUE = 2, FD = 3, REC = 4, CUR = 5, END = 6, NEEDED = 7, B0 = 8,
				B1 = 9, B2 = 10, B3 = 11, I = 12;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		final int SCRATCH = WasmLispCompiler.BYTE_SCRATCH_ADDR;

		// fd = stream is an i31 handle ? i31.get_s(stream) : 0 (stdin). The test is
		// "is a handle", not "is nil": nil AND the t designator (what *standard-input*
		// holds by default) are both standard input, and a ref.cast on t would trap.
		getLocal(w, STREAM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		i32(w, 0);
		w.write(Instruction.END);
		setLocal(w, FD);
		// A negative handle is a string input stream: decode a UTF-8 sequence within the
		// [cursor, end) range.
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, FD);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		// cur = mem[rec + 4]; end = mem[rec + 8];
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, CUR);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, END);
		// if (cur >= end): EOF
		getLocal(w, CUR);
		getLocal(w, END);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		emitReadCharEof(w, EOF_ERROR_P, EOF_VALUE);
		w.write(Instruction.END);
		// b0 = mem_u8[cur]; needed = utf8ByteCount(b0);
		getLocal(w, CUR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B0);
		emitUtf8ByteCount(w, B0);
		setLocal(w, NEEDED);
		// If cur + needed > end: needed = 1 (truncated tail -- return bare lead byte).
		getLocal(w, CUR);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		getLocal(w, END);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		setLocal(w, NEEDED);
		w.write(Instruction.END);
		// Load b1..b3 as needed.
		getLocal(w, NEEDED);
		i32(w, 2);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B1);
		w.write(Instruction.END);
		getLocal(w, NEEDED);
		i32(w, 3);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B2);
		w.write(Instruction.END);
		getLocal(w, NEEDED);
		i32(w, 4);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B3);
		w.write(Instruction.END);
		// mem[rec + 4] = cur + needed
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		getLocal(w, CUR);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return TYPE_CHAR(decodeUtf8(needed, b0, b1, b2, b3))
		emitUtf8DecodeFromLocals(w, NEEDED, B0, B1, B2, B3);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// WASI fd branch. A peek on this fd may have parked a whole code point in the
		// one-slot pushback (a fd cannot be un-read): drain it before touching the fd.
		loadMem32(w, WasmLispCompiler.PEEK_FD_ADDR);
		getLocal(w, FD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, WasmLispCompiler.PEEK_FD_ADDR);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		loadMem32(w, WasmLispCompiler.PEEK_CP_ADDR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// Read the lead byte via fd_read, then per-byte follow-ups.
		// iov.ptr = SCRATCH ; iov.len = 1
		i32(w, IOV);
		i32(w, SCRATCH);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd_read(fd, IOV, 1, NWRITTEN) ; drop errno
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		// if (mem[NWRITTEN] == 0): EOF
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		emitReadCharEof(w, EOF_ERROR_P, EOF_VALUE);
		w.write(Instruction.END);
		// b0 = mem_u8[SCRATCH]; needed = utf8ByteCount(b0);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B0);
		emitUtf8ByteCount(w, B0);
		setLocal(w, NEEDED);
		// i = 1; loop { if i >= needed break; fd_read(fd, IOV, 1, NWRITTEN); if nread==0
		// { needed = i; break; } mem_u8[SCRATCH+i] = mem_u8[SCRATCH]; i++; }
		i32(w, 1);
		setLocal(w, I);
		w.write(Instruction.BLOCK, 0x40);
		w.write(Instruction.LOOP, 0x40);
		// if (i >= needed) break out of block.
		getLocal(w, I);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		// iov points at SCRATCH already; we reuse it. Read one byte into SCRATCH.
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_READ);
		w.write(Instruction.DROP);
		// If nread == 0: needed = i; break.
		loadMem32(w, NWRITTEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, I);
		setLocal(w, NEEDED);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(2);
		w.write(Instruction.END);
		// Stash the just-read byte into local B1/B2/B3 by index. Emitted as a small
		// switch on i to keep the loop body free of writable memory (SCRATCH is single-
		// byte, reused for the next read).
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B1);
		w.write(Instruction.END);
		getLocal(w, I);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B2);
		w.write(Instruction.END);
		getLocal(w, I);
		i32(w, 3);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B3);
		w.write(Instruction.END);
		// i = i + 1; continue.
		getLocal(w, I);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, I);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // loop
		w.write(Instruction.END); // block
		// If needed collapsed to 0 mid-sequence (very first follow-up EOF), clamp to 1
		// so we return the lead byte as a bare CHARACTER rather than dispatching to a
		// zero-count decode.
		getLocal(w, NEEDED);
		i32(w, 1);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		setLocal(w, NEEDED);
		w.write(Instruction.END);
		// return TYPE_CHAR(decodeUtf8(needed, b0, b1, b2, b3))
		emitUtf8DecodeFromLocals(w, NEEDED, B0, B1, B2, B3);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the _peek_char(stream, eof-error-p, eof-value) function body: the next
	 * character of the stream, LEFT IN PLACE. A string input stream (a negative i31
	 * handle) decodes the UTF-8 sequence at its record's cursor WITHOUT advancing it, so
	 * peeking there is exact and unlimited. A WASI fd cannot be un-read, so the code
	 * point is read through {@code _read_char} and parked in the one-slot pushback
	 * ({@code PEEK_FD_ADDR}/{@code PEEK_CP_ADDR}) that {@code _read_char} drains first --
	 * keyed on the fd, so a peek on one stream is never consumed by a read on another. A
	 * repeated peek answers the parked code point.
	 * @return the function body bytes
	 */
	static byte[] buildPeekCharBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: STREAM=0 (ref), EOF_ERROR_P=1 (ref), EOF_VALUE=2 (ref) ; i32 locals:
		// FD=3, REC=4, CUR=5, END=6, NEEDED=7, B0=8, B1=9, B2=10, B3=11 ; ref local:
		// C=12
		w.write(2);
		w.write(9);
		w.write(Type.I32);
		w.write(1);
		w.writeRefType(true, Type.EQ.code());
		final int STREAM = 0, EOF_ERROR_P = 1, EOF_VALUE = 2, FD = 3, REC = 4, CUR = 5, END = 6, NEEDED = 7, B0 = 8,
				B1 = 9, B2 = 10, B3 = 11, C = 12;

		// fd = stream is an i31 handle ? i31.get_s(stream) : 0 (stdin). The test is
		// "is a handle", not "is nil": nil AND the t designator (what *standard-input*
		// holds by default) are both standard input, and a ref.cast on t would trap.
		getLocal(w, STREAM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		i32(w, 0);
		w.write(Instruction.END);
		setLocal(w, FD);
		// A negative handle is a string input stream: decode at the cursor, leave it.
		getLocal(w, FD);
		i32(w, 0);
		w.write(Instruction.I32_LT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 0);
		getLocal(w, FD);
		w.write(Instruction.I32_SUB);
		setLocal(w, REC);
		getLocal(w, REC);
		i32(w, 4);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, CUR);
		getLocal(w, REC);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
		setLocal(w, END);
		getLocal(w, CUR);
		getLocal(w, END);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		emitReadCharEof(w, EOF_ERROR_P, EOF_VALUE);
		w.write(Instruction.END);
		getLocal(w, CUR);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B0);
		emitUtf8ByteCount(w, B0);
		setLocal(w, NEEDED);
		// If cur + needed > end: needed = 1 (truncated tail -- bare lead byte).
		getLocal(w, CUR);
		getLocal(w, NEEDED);
		w.write(Instruction.I32_ADD);
		getLocal(w, END);
		w.write(Instruction.I32_GT_S);
		w.write(Instruction.IF, 0x40);
		i32(w, 1);
		setLocal(w, NEEDED);
		w.write(Instruction.END);
		getLocal(w, NEEDED);
		i32(w, 2);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B1);
		w.write(Instruction.END);
		getLocal(w, NEEDED);
		i32(w, 3);
		w.write(Instruction.I32_GE_S);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 2);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B2);
		w.write(Instruction.END);
		getLocal(w, NEEDED);
		i32(w, 4);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		getLocal(w, CUR);
		i32(w, 3);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		setLocal(w, B3);
		w.write(Instruction.END);
		// The cursor is NOT advanced -- that is the whole difference from _read_char.
		emitUtf8DecodeFromLocals(w, NEEDED, B0, B1, B2, B3);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// WASI fd: answer the parked code point when this fd already has one.
		loadMem32(w, WasmLispCompiler.PEEK_FD_ADDR);
		getLocal(w, FD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		loadMem32(w, WasmLispCompiler.PEEK_CP_ADDR);
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_NEW);
		w.writeUnsignedLeb128(WasmLispCompiler.TYPE_CHAR);
		w.write(Instruction.RETURN);
		w.write(Instruction.END);
		// c = _read_char(stream, nil, nil) -- nil eof-error-p, so end of file is null.
		getLocal(w, STREAM);
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.REF_NULL);
		w.writeHeapType(Type.EQ.code());
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_READ_CHAR);
		setLocal(w, C);
		getLocal(w, C);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		emitReadCharEof(w, EOF_ERROR_P, EOF_VALUE);
		w.write(Instruction.END);
		// Park it: mem[PEEK_FD] = fd + 1 ; mem[PEEK_CP] = code point.
		i32(w, WasmLispCompiler.PEEK_FD_ADDR);
		getLocal(w, FD);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, WasmLispCompiler.PEEK_CP_ADDR);
		getLocal(w, C);
		refCast(w, WasmLispCompiler.TYPE_CHAR);
		structGet(w, WasmLispCompiler.TYPE_CHAR, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, C);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// EOF: return eof-value when eof-error-p is nil, trap otherwise (like _read_byte).
	private static void emitReadCharEof(WasmWriter w, int eofErrorP, int eofValue) {
		getLocal(w, eofErrorP);
		w.write(Instruction.REF_IS_NULL);
		w.write(Instruction.IF, 0x40);
		getLocal(w, eofValue);
		w.write(Instruction.RETURN);
		w.write(Instruction.ELSE);
		w.write(Instruction.UNREACHABLE);
		w.write(Instruction.END);
	}

	// Pushes the UTF-8 sequence length (1..4) implied by a lead byte, based on the
	// same high-bit ranges as _str_char_at: [0..0x80)=1, [0x80..0xE0)=2,
	// [0xE0..0xF0)=3, else 4.
	private static void emitUtf8ByteCount(WasmWriter w, int b0Local) {
		getLocal(w, b0Local);
		i32(w, 0x80);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 1);
		w.write(Instruction.ELSE);
		getLocal(w, b0Local);
		i32(w, 0xE0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 2);
		w.write(Instruction.ELSE);
		getLocal(w, b0Local);
		i32(w, 0xF0);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.IF);
		w.write(Type.I32);
		i32(w, 3);
		w.write(Instruction.ELSE);
		i32(w, 4);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	// Pushes the decoded Unicode code point given the sequence length in {@code
	// neededLocal} and the 1..4 bytes in {@code b0Local}..{@code b3Local}. Follows the
	// same 6-bit continuation decoding as _str_char_at.
	private static void emitUtf8DecodeFromLocals(WasmWriter w, int neededLocal, int b0Local, int b1Local, int b2Local,
			int b3Local) {
		getLocal(w, neededLocal);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, b0Local);
		w.write(Instruction.ELSE);
		getLocal(w, neededLocal);
		i32(w, 2);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// ((b0 & 0x1F) << 6) | (b1 & 0x3F)
		getLocal(w, b0Local);
		i32(w, 0x1F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		getLocal(w, b1Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.ELSE);
		getLocal(w, neededLocal);
		i32(w, 3);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF);
		w.write(Type.I32);
		// ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F)
		getLocal(w, b0Local);
		i32(w, 0x0F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		getLocal(w, b1Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		getLocal(w, b2Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.ELSE);
		// 4-byte: ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) |
		// (b3 & 0x3F)
		getLocal(w, b0Local);
		i32(w, 0x07);
		w.write(Instruction.I32_AND);
		i32(w, 18);
		w.write(Instruction.I32_SHL);
		getLocal(w, b1Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 12);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		getLocal(w, b2Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		i32(w, 6);
		w.write(Instruction.I32_SHL);
		w.write(Instruction.I32_OR);
		getLocal(w, b3Local);
		i32(w, 0x3F);
		w.write(Instruction.I32_AND);
		w.write(Instruction.I32_OR);
		w.write(Instruction.END);
		w.write(Instruction.END);
		w.write(Instruction.END);
	}

	/**
	 * Builds the _write_byte(byte, stream) function body. Writes the byte's low 8 bits as
	 * one raw byte to the stream's file descriptor via fd_write -- no quote framing, no
	 * newline -- and returns the byte.
	 *
	 * <p>
	 * The designator mirror of {@link #buildReadByteBody}: a non-handle -- nil, or the
	 * {@code t} an unbound {@code *standard-output*} reads as -- is fd 1, the process
	 * standard output. The reserved handle 2 needs no branch of its own here: fd 2 IS
	 * stderr for {@code fd_write}, the same reason the string writers have none.
	 * @return the function body bytes
	 */
	static byte[] buildWriteByteBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: BYTE=0 (ref), STREAM=1 (ref) ; i32 local: FD=2
		w.write(1);
		w.write(1);
		w.write(Type.I32);
		final int BYTE = 0, STREAM = 1, FD = 2;
		final int IOV = WasmLispCompiler.IOV_OFFSET;
		final int NWRITTEN = WasmLispCompiler.NWRITTEN_OFFSET;
		final int SCRATCH = WasmLispCompiler.BYTE_SCRATCH_ADDR;

		// mem_u8[BYTE_SCRATCH_ADDR] = i31.get_s(byte)
		i32(w, SCRATCH);
		getLocal(w, BYTE);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		// iov.ptr = BYTE_SCRATCH_ADDR ; iov.len = 1
		i32(w, IOV);
		i32(w, SCRATCH);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, IOV + 4);
		i32(w, 1);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// fd = stream is an i31 handle ? i31.get_s(stream) : 1 (stdout)
		getLocal(w, STREAM);
		w.write(Instruction.GC_PREFIX, Instruction.REF_TEST);
		w.writeHeapType(Type.I31.code());
		w.write(Instruction.IF);
		w.write(Type.I32);
		getLocal(w, STREAM);
		refCast(w, Type.I31.code());
		w.write(Instruction.GC_PREFIX, Instruction.I31_GET_S);
		w.write(Instruction.ELSE);
		i32(w, 1);
		w.write(Instruction.END);
		setLocal(w, FD);
		// fd_write(fd, IOV, 1, NWRITTEN) ; drop errno
		getLocal(w, FD);
		i32(w, IOV);
		i32(w, 1);
		i32(w, NWRITTEN);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(WasmLispCompiler.FUNC_FD_WRITE);
		w.write(Instruction.DROP);
		// A raw octet moves the standard-output column exactly like a character does:
		// if (fd == 1) LINE_START = (byte != '\n'), the same flag _write_str keeps. Any
		// other descriptor leaves it alone -- a file write must not disturb stdout's.
		getLocal(w, FD);
		i32(w, 1);
		w.write(Instruction.I32_EQ);
		w.write(Instruction.IF, 0x40);
		i32(w, WasmLispCompiler.LINE_START_ADDR);
		i32(w, SCRATCH);
		w.write(Instruction.I32_LOAD8_U, 0x00, 0x00);
		i32(w, 10);
		w.write(Instruction.I32_NE);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		w.write(Instruction.END);
		// return the byte
		getLocal(w, BYTE);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the {@code --no-wasi} {@code fd_write} stub: a SINK. It reports the whole
	 * iovec as written ({@code *nwritten = iovs[0].len}) and returns errno 0, so a
	 * reactor's {@code print} / {@code format t} -- and, far more importantly, a
	 * quickloaded library that logs while it loads -- discards its bytes instead of
	 * trapping the instance at {@code _initialize}.
	 *
	 * <p>
	 * <strong>Why output gets a sink</strong> (the re-evaluation trigger, decided
	 * 2026-08-07): a reactor host hands the module no file descriptors at all, so there
	 * IS no destination for stdout/stderr -- discarding loses only the bytes, and the
	 * alternative was killing the whole instance for a log line, with a bare
	 * {@code unreachable} naming nothing. This was the first stub to answer, and the
	 * general rule grew out of it: a stub may answer when the answer is TRUE OF THIS
	 * MODULE (no destination, no environment, no files), and may not when answering would
	 * return data the program cannot tell from real -- which is why {@code fd_read} and
	 * {@code clock_time_get} are still bare {@code unreachable} (see
	 * {@code .kb/wasm-export-no-wasi.md} for the whole table).
	 *
	 * <p>
	 * Every emitter here calls {@code fd_write} with ONE iovec and drops the errno, so
	 * the single-iovec accounting is exact; a future caller passing more (or looping on
	 * {@code *nwritten}) must widen this.
	 * @return the function body bytes
	 */
	static byte[] buildNoWasiFdWriteSinkBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: FD=0, IOVS=1, IOVS_LEN=2, NWRITTEN=3 (all i32); no locals.
		w.write(0);
		final int IOVS = 1, NWRITTEN = 3;
		// *nwritten = iovs[0].len
		getLocal(w, NWRITTEN);
		getLocal(w, IOVS);
		w.write(Instruction.I32_LOAD, 0x02, 0x04);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		// return 0 (success)
		i32(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the {@code --no-wasi} {@code environ_sizes_get} stub: reports an EMPTY
	 * environment (0 variables, 0 bytes) and returns errno 0, so
	 * {@code (uiop:getenv "X")} answers {@code nil} instead of trapping.
	 *
	 * <p>
	 * <strong>Why this is truth, not fabrication</strong> (the re-evaluation trigger,
	 * decided 2026-08-09): "no environment variables are set" is a state every real WASI
	 * host can produce -- {@code wasmtime run} without {@code --env} is exactly it -- and
	 * a reactor host hands the module no environment at all, so reporting zero is a fact
	 * about this module rather than data invented to look like a host's. It is the same
	 * shape of answer as the {@code fd_write} sink and the opposite of a
	 * {@code clock_time_get} answering 0, which would name a time that is not the time.
	 * Every caller already handles the unset case: {@code getenv} is a lookup that may
	 * miss.
	 *
	 * <p>
	 * This is the second half of {@code smart-buffer}'s one load-time form
	 * ({@code (merge-pathnames (format nil "smart-buffer-~36R" (random ...))
	 * (uiop:default-temporary-directory))}) -- {@code default-temporary-directory} reads
	 * {@code TMPDIR} -- and so of the {@code lack-request} chain that could not be
	 * instantiated on a Worker.
	 * @return the function body bytes
	 */
	static byte[] buildNoWasiEnvironSizesGetBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: COUNT_OUT=0, BUFSIZE_OUT=1 (both i32); no locals.
		w.write(0);
		final int COUNT_OUT = 0, BUFSIZE_OUT = 1;
		getLocal(w, COUNT_OUT);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		getLocal(w, BUFSIZE_OUT);
		i32(w, 0);
		w.write(Instruction.I32_STORE, 0x02, 0x00);
		i32(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/** preview1 {@code errno::noent} -- "no such file or directory". */
	static final int ERRNO_NOENT = 44;

	/** preview1 {@code errno::badf} -- "bad file descriptor". */
	static final int ERRNO_BADF = 8;

	/**
	 * Builds a {@code --no-wasi} stub that simply REPORTS the given preview1 errno: the
	 * whole body is {@code i32.const errno}, valid for every i32-returning WASI signature
	 * whatever its parameters, because the parameters are just ignored.
	 *
	 * <p>
	 * This is how the FILESYSTEM family answers instead of trapping, and it needed no new
	 * logic anywhere else: {@code _open}, {@code _probe_file}, {@code _list_directory}
	 * and {@code _load} all already turn a non-zero {@code path_open} errno into
	 * {@code nil} -- deliberately, because a wasm trap is not catchable and a library
	 * probing for an OPTIONAL file must degrade rather than abort. Handing them
	 * {@code ENOENT} therefore makes {@code probe-file} / {@code directory} answer
	 * {@code nil} and the {@code open} call sites signal a real, catchable Lisp error,
	 * which is the truth about a module that has no filesystem at all -- no file exists,
	 * so no file is found. {@code fd_close} / {@code fd_readdir} get {@code EBADF} for
	 * the same reason: with every open failing there is no descriptor they could be
	 * handed, and "that is not a descriptor" is again a true answer rather than an
	 * invented one.
	 *
	 * <p>
	 * {@code environ_get} uses it with errno 0: an EMPTY environment has no pointers and
	 * no bytes to write, so success with nothing written is the complete answer (the
	 * caller's loop is bounded by the count {@link #buildNoWasiEnvironSizesGetBody()}
	 * just reported, which is zero).
	 *
	 * <p>
	 * The syntactic {@code (with-open-file ...)} / {@code (open ...)} rewrite in
	 * {@code compiler/NoWasiFilesystemStubs} stays, and is not made redundant by this: it
	 * is what gives those two forms a message naming WASI instead of a generic "cannot
	 * open", and -- the reason it runs first -- what drops the dead
	 * {@code read}/{@code eval} bodies inside them out of the module entirely
	 * ({@code .kb/optimize-dead-code-elimination.md}). This stub is the backstop for
	 * every path that rewrite cannot see: {@code probe-file}, {@code directory},
	 * {@code load}, and an {@code open} reached through {@code funcall}.
	 * @param errno the preview1 errno to report
	 * @return the function body bytes
	 */
	static byte[] buildNoWasiErrnoBody(int errno) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// no locals; the parameters (whatever the signature has) are ignored.
		w.write(0);
		i32(w, errno);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the body of {@code __ronto_seed_random (i64) -> ()}, the host's way to
	 * replace the {@code --no-wasi} generator's constant start state with real entropy:
	 * it stores the argument into {@link WasmLispCompiler#RANDOM_STATE_ADDR}, and the
	 * next SplitMix64 step continues from there.
	 *
	 * <p>
	 * This is what a JavaScript host calls -- once, right after instantiation and BEFORE
	 * {@code _initialize}, so even a library's load-time {@code (random ...)} draws from
	 * the host's entropy:
	 * {@code seed(new BigUint64Array(crypto.getRandomValues(new Uint8Array(8)).buffer)[0])}.
	 * It is an EXPORT rather than an import on purpose: an import would make the module
	 * un-instantiable with an empty import object, which is the entire contract of
	 * {@code --no-wasi}. A host that does not call it gets the deterministic sequence,
	 * unchanged.
	 *
	 * <p>
	 * Seeding does NOT turn {@code rontolisp:random-bytes} back on. SplitMix64 is
	 * invertible from a single output, so a host-seeded stream is unpredictable but not
	 * cryptographically strong, and the entropy API keeps signalling rather than shipping
	 * something that only looks like a CSPRNG.
	 *
	 * <p>
	 * Core-module shape only ({@code --no-wasi} without {@code --component}). A reactor
	 * COMPONENT would have to lift this into its WIT world to expose it, which is a
	 * world-shape decision rather than a core export -- and its top level runs at
	 * instantiation anyway, so there is no window before the load-time draws.
	 * @return the function body bytes
	 */
	static byte[] buildSeedRandomBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: SEED=0 (i64); no locals.
		w.write(0);
		i32(w, WasmLispCompiler.RANDOM_STATE_ADDR);
		getLocal(w, 0);
		w.write(Instruction.I64_STORE, 0x03, 0x00);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the body of {@code __ronto_set_time (i64) -> ()}, the host's way to give a
	 * {@code --no-wasi} module a clock: it stores the argument -- nanoseconds since the
	 * Unix epoch, preview1's own {@code clock_time_get} unit -- into
	 * {@link WasmLispCompiler#HOST_TIME_ADDR}, which is what {@code get-universal-time} /
	 * {@code get-internal-real-time} / {@code get-internal-run-time} read from there on.
	 *
	 * <p>
	 * <strong>Why this does not break the "a stub may not invent a value" rule -- it is
	 * the rule's other half.</strong> A stub answering 0 would name 1970; a stub counting
	 * its own calls would hand back numbers that look like milliseconds and are not. The
	 * host, though, genuinely knows the time, and handing it over is the same move
	 * {@link #buildSeedRandomBody()} already makes for entropy: the value the program
	 * reads IS a real reading, taken by the only party in a position to take one. Until
	 * the host calls this, the cell is zero and the three built-ins keep signalling --
	 * the constant start state that is harmless for {@code random} would be exactly the
	 * 1970 lie here.
	 *
	 * <p>
	 * The clock does not ADVANCE between host calls, and that is the honest shape rather
	 * than a degraded one: a Cloudflare Worker's own clock is frozen for the duration of
	 * a request (a deliberate timing-attack mitigation), so a value that only moves when
	 * the host moves it is exactly what that platform has. A host that wants it to
	 * advance calls the setter again -- per request is the natural rhythm, next to the
	 * seed hook in every {@code examples/cloudflare-workers/*}{@code /src/index.js}.
	 * Nothing inside the module can move it, which is why {@code sleep} SIGNALS on
	 * {@code --no-wasi} instead of spinning on the clock the way Preview 1 does: with no
	 * timer to park on and no clock that can advance while a call is running, the spin
	 * could never end.
	 *
	 * <p>
	 * Core-module shape only ({@code --no-wasi} without {@code --component}), for the
	 * seed hook's reason: a reactor component runs its top level at INSTANTIATION, so
	 * there is no window in which a host could set the time before the load-time reads,
	 * and exposing it there would mean lifting it into the WIT world -- a world-shape
	 * decision, not a core export.
	 * @return the function body bytes
	 */
	static byte[] buildSetTimeBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// param: NANOS=0 (i64); no locals.
		w.write(0);
		i32(w, WasmLispCompiler.HOST_TIME_ADDR);
		getLocal(w, 0);
		w.write(Instruction.I64_STORE, 0x03, 0x00);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	/**
	 * Builds the {@code --no-wasi --host-random} {@code random_get} slot: instead of the
	 * module-local generator below, the slot FORWARDS its two parameters to a host import
	 * and returns the host's errno, so every {@code random} draw -- including one in a
	 * quickloaded library's top-level form, which never learns where the bytes came from
	 * -- is the host's entropy.
	 *
	 * <p>
	 * The signature is preview1's {@code random_get(buf, len) -> errno} exactly, so a
	 * host that already has a WASI implementation can pass it straight through; the
	 * import module is {@code env} rather than {@code wasi_snapshot_preview1} because the
	 * module still imports no WASI function -- it imports ONE host function the flag
	 * asked for.
	 *
	 * <p>
	 * This is what makes {@code rontolisp:random-bytes} sound again
	 * ({@code WasmExprCompiler} un-gates {@code rontolisp::%random-byte} when it is in
	 * effect): the entropy is genuinely the host's, so nothing is being passed off as
	 * something it is not. It is also why {@code __ronto_seed_random} is NOT emitted
	 * alongside it -- there is no module-local state left to seed.
	 * @param placeholderFuncIndex the placeholder call index of the host import
	 * ({@code WasmImportCompiler.PLACEHOLDER_FUNC_BASE + ordinal}), which
	 * {@link am.ik.wasm.WasmImportInjector} rewrites to the import's real function index
	 * @return the function body bytes
	 */
	static byte[] buildNoWasiHostRandomGetBody(int placeholderFuncIndex) {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: BUF=0, LEN=1 (both i32); no locals.
		w.write(0);
		getLocal(w, 0);
		getLocal(w, 1);
		w.write(Instruction.CALL);
		w.writeUnsignedLeb128(placeholderFuncIndex);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// SplitMix64 constants: the golden-ratio increment and the two mixing multipliers.
	private static final long SM64_GAMMA = 0x9E3779B97F4A7C15L;

	private static final long SM64_MIX1 = 0xBF58476D1CE4E5B9L;

	private static final long SM64_MIX2 = 0x94D049BB133111EBL;

	/**
	 * Builds the {@code --no-wasi} {@code random_get} stub: a SplitMix64 generator over
	 * {@link WasmLispCompiler#RANDOM_STATE_ADDR} that fills the caller's buffer with
	 * {@code buf_len} pseudo-random bytes and returns errno 0. Nothing is imported, so a
	 * reactor's {@code (random n)} -- including one in a TOP-LEVEL form of a quickloaded
	 * library -- answers instead of killing the instance.
	 *
	 * <p>
	 * <strong>Why this is not the fabricated input the sink rule forbids</strong> (the
	 * re-evaluation trigger, decided 2026-08-09): {@code random} is not an entropy API.
	 * CL specifies it as a pseudo-random draw from {@code *random-state*}, and a
	 * conforming image may start from a fixed state -- here {@code make-random-state}
	 * already answers {@code nil}, so no state object is observable and "the sequence
	 * repeats" is inside the contract, not a lie about the host. The primitive that DOES
	 * promise entropy, {@code rontolisp::%random-byte} behind
	 * {@code rontolisp:random-bytes}, is therefore NOT served from here on
	 * {@code --no-wasi}: {@code WasmExprCompiler} lowers it to a call-time error instead,
	 * because answering it from a fixed-seed PRNG would be exactly the "data the program
	 * cannot tell from real" the {@code fd_read} / {@code clock_time_get} stubs still
	 * trap over.
	 *
	 * <p>
	 * The state cell starts at the zero of untouched linear memory, so every instance of
	 * one module walks the same sequence. That is accepted, not overlooked: a core module
	 * with no imports has no entropy available at all, and the alternatives (an import,
	 * or a host poke into a frozen linear-memory address) both cost the zero-import
	 * property that is the whole point of the flag. If a consumer ever needs per-instance
	 * sequences, the cell above is the single place to write and the answer is an
	 * exported {@code __ronto_seed_random} next to {@code __ronto_alloc}.
	 * @return the function body bytes
	 */
	static byte[] buildNoWasiRandomGetBody() {
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		WasmWriter w = new WasmWriter(body);
		// params: BUF=0, LEN=1 (both i32); i64 locals: S=2 (the generator's scratch),
		// T=3 (the tail's byte shifter).
		w.write(1);
		w.write(2);
		w.write(Type.I64);
		final int BUF = 0, LEN = 1, S = 2, T = 3;
		// while (len >= 8) { mem64[buf] = next(); buf += 8; len -= 8; }
		w.write(Instruction.BLOCK, 0x40); // $done
		w.write(Instruction.LOOP, 0x40); // $chunk
		getLocal(w, LEN);
		i32(w, 8);
		w.write(Instruction.I32_LT_U);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(1);
		getLocal(w, BUF);
		emitSplitMix64Next(w, S);
		// align=0: a caller's buffer need not be 8-aligned (the two rontolisp call sites
		// pass RANDOM_SCRATCH_ADDR, which is, but random_get's contract does not say so).
		w.write(Instruction.I64_STORE, 0x00, 0x00);
		getLocal(w, BUF);
		i32(w, 8);
		w.write(Instruction.I32_ADD);
		setLocal(w, BUF);
		getLocal(w, LEN);
		i32(w, 8);
		w.write(Instruction.I32_SUB);
		setLocal(w, LEN);
		w.write(Instruction.BR);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // $chunk
		w.write(Instruction.END); // $done
		// Tail (len is now 0..7): one more draw, spent one byte at a time.
		w.write(Instruction.BLOCK, 0x40); // $tailDone
		getLocal(w, LEN);
		w.write(Instruction.I32_EQZ);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		emitSplitMix64Next(w, S);
		setLocal(w, T);
		w.write(Instruction.LOOP, 0x40); // $tail
		getLocal(w, BUF);
		getLocal(w, T);
		w.write(Instruction.I32_WRAP_I64);
		w.write(Instruction.I32_STORE8, 0x00, 0x00);
		getLocal(w, BUF);
		i32(w, 1);
		w.write(Instruction.I32_ADD);
		setLocal(w, BUF);
		getLocal(w, T);
		i64(w, 8);
		w.write(Instruction.I64_SHR_U);
		setLocal(w, T);
		getLocal(w, LEN);
		i32(w, 1);
		w.write(Instruction.I32_SUB);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(LEN);
		w.write(Instruction.BR_IF);
		w.writeUnsignedLeb128(0);
		w.write(Instruction.END); // $tail
		w.write(Instruction.END); // $tailDone
		// errno 0
		i32(w, 0);
		w.write(Instruction.END);
		return body.toByteArray();
	}

	// One SplitMix64 step, leaving the u64 draw on the stack: the state cell advances by
	// the golden-ratio gamma, and the new state is mixed through two xor-shift-multiply
	// rounds and a final xor-shift. `scratch` is an i64 local the caller does not need
	// across this sequence.
	private static void emitSplitMix64Next(WasmWriter w, int scratch) {
		// mem64[RANDOM_STATE_ADDR] += GAMMA ; scratch = the new state
		i32(w, WasmLispCompiler.RANDOM_STATE_ADDR);
		i32(w, WasmLispCompiler.RANDOM_STATE_ADDR);
		w.write(Instruction.I64_LOAD, 0x03, 0x00);
		i64(w, SM64_GAMMA);
		w.write(Instruction.I64_ADD);
		w.write(Instruction.TEE_LOCAL);
		w.writeUnsignedLeb128(scratch);
		w.write(Instruction.I64_STORE, 0x03, 0x00);
		emitSplitMix64Mix(w, scratch, 30, SM64_MIX1);
		emitSplitMix64Mix(w, scratch, 27, SM64_MIX2);
		// push scratch ^ (scratch >>> 31)
		getLocal(w, scratch);
		getLocal(w, scratch);
		i64(w, 31);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I64_XOR);
	}

	// scratch = (scratch ^ (scratch >>> shift)) * multiplier
	private static void emitSplitMix64Mix(WasmWriter w, int scratch, int shift, long multiplier) {
		getLocal(w, scratch);
		getLocal(w, scratch);
		i64(w, shift);
		w.write(Instruction.I64_SHR_U);
		w.write(Instruction.I64_XOR);
		i64(w, multiplier);
		w.write(Instruction.I64_MUL);
		setLocal(w, scratch);
	}

	// === low-level emit helpers ===

	private static void getLocal(WasmWriter w, int slot) {
		w.write(Instruction.GET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void setLocal(WasmWriter w, int slot) {
		w.write(Instruction.SET_LOCAL);
		w.writeUnsignedLeb128(slot);
	}

	private static void i32(WasmWriter w, int value) {
		w.write(Instruction.I32_CONST);
		w.writeSignedLeb128(value);
	}

	private static void i64(WasmWriter w, long value) {
		w.write(Instruction.I64_CONST);
		w.writeSignedLeb128(value);
	}

	private static void structGet(WasmWriter w, int type, int field) {
		w.write(Instruction.GC_PREFIX, Instruction.STRUCT_GET);
		w.writeUnsignedLeb128(type);
		w.writeUnsignedLeb128(field);
	}

	private static void refCast(WasmWriter w, int heapType) {
		w.write(Instruction.GC_PREFIX, Instruction.REF_CAST);
		w.writeHeapType(heapType);
	}

	private static void loadMem32(WasmWriter w, int addr) {
		i32(w, addr);
		w.write(Instruction.I32_LOAD, 0x02, 0x00);
	}

}
