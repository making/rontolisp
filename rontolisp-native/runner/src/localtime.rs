//! The local time zone host (`rltime`): what `decode-universal-time`,
//! `encode-universal-time` and `get-decoded-time` ask when no time zone is given.
//!
//! WASI has no time zone interface, and the module cannot read the zone itself here: the
//! runner preopens `/`, but `/etc/localtime` is a symbolic link to an absolute path on macOS
//! and on most Linux distributions, which a preopen's sandbox refuses to follow. So the
//! runner answers from the C library, as a native program's `localtime_r` does -- `TZ`
//! (a zone name, a file or a POSIX rule string), else `/etc/localtime`; musl's on Linux,
//! the system's on macOS.

use std::ffi::{c_char, c_int, c_long};
use std::mem::MaybeUninit;
use std::sync::Once;

use wasmtime::Linker;
use wasmtime_wasi::p1::WasiP1Ctx;

/// The import module a module asking for the local zone names (`eval/LocalTimeLibrary`).
pub const MODULE: &str = "rltime";

/// `struct tm` with the BSD/GNU fields both C libraries define after the standard nine.
#[repr(C)]
struct Tm {
    tm_sec: c_int,
    tm_min: c_int,
    tm_hour: c_int,
    tm_mday: c_int,
    tm_mon: c_int,
    tm_year: c_int,
    tm_wday: c_int,
    tm_yday: c_int,
    tm_isdst: c_int,
    tm_gmtoff: c_long,
    tm_zone: *const c_char,
}

unsafe extern "C" {
    fn tzset();
    fn localtime_r(time: *const i64, result: *mut Tm) -> *mut Tm;
}

/// `local_offset(unix-seconds) -> encoded`: the zone's total offset east of UTC in seconds
/// at that instant, doubled, plus 1 under daylight saving time -- the `%local-time-offset`
/// encoding. 0 (UTC) when the C library cannot convert the instant.
pub fn add_to_linker(linker: &mut Linker<WasiP1Ctx>) -> wasmtime::Result<()> {
    linker.func_wrap(MODULE, "local_offset", |unix: i64| -> i64 {
        static TZSET: Once = Once::new();
        // SAFETY: tzset only reads the environment and the zone files.
        TZSET.call_once(|| unsafe { tzset() });
        local_offset(unix)
    })?;
    Ok(())
}

fn local_offset(unix: i64) -> i64 {
    let mut tm = MaybeUninit::<Tm>::zeroed();
    // SAFETY: localtime_r writes the struct it is handed and nothing else.
    if unsafe { localtime_r(&unix, tm.as_mut_ptr()) }.is_null() {
        return 0;
    }
    // SAFETY: a non-null answer filled the struct.
    let tm = unsafe { tm.assume_init() };
    i64::from(tm.tm_gmtoff) * 2 + i64::from(tm.tm_isdst > 0)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Each zone through `TZ`, reread by `tzset`: the one test in this binary that touches
    /// the environment, so no other thread reads it meanwhile.
    #[test]
    fn answers_the_zone_tz_names_daylight_saving_time_included() {
        // 2026-01-15 and 2026-07-15, 00:00 UTC.
        let (january, july) = (1_768_435_200, 1_784_073_600);
        for (tz, winter, summer) in [
            ("UTC", 0, 0),
            ("Asia/Tokyo", 9 * 3600 * 2, 9 * 3600 * 2),
            ("America/New_York", -5 * 3600 * 2, -4 * 3600 * 2 + 1),
            ("Australia/Sydney", 11 * 3600 * 2 + 1, 10 * 3600 * 2),
            ("Asia/Kolkata", 19_800 * 2, 19_800 * 2),
            ("EST5EDT,M3.2.0,M11.1.0", -5 * 3600 * 2, -4 * 3600 * 2 + 1),
        ] {
            // SAFETY: see the doc comment.
            unsafe {
                std::env::set_var("TZ", tz);
                tzset();
            }
            assert_eq!(local_offset(january), winter, "{tz} in January");
            assert_eq!(local_offset(july), summer, "{tz} in July");
        }
    }
}
