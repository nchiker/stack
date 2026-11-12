//! The one source of calendar ("wall clock") time for Stack desktop.
//!
//! Everything that stores a time or decides something by the date (timestamps in
//! the library, certificate validity, "last sync N minutes ago", file names) reads
//! it here, so a test can fix it with [`fixed`] and get the same answer every run.
//! `std::time::SystemTime::now` is refused elsewhere by clippy (see clippy.toml).
//! Timeouts and deadlines measure elapsed time with `std::time::Instant`, which is
//! monotonic and stays real: a frozen clock must never make a network wait hang.

use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

const REAL: i64 = i64::MIN;
static FIXED: AtomicI64 = AtomicI64::new(REAL);
static EXCLUSIVE: Mutex<()> = Mutex::new(());

/// Milliseconds since 1970 (0 if the system clock is before 1970).
pub fn now_millis() -> i64 {
    match FIXED.load(Ordering::SeqCst) {
        REAL => system_millis(),
        fixed => fixed,
    }
}

/// Seconds since 1970.
pub fn now_secs() -> u64 {
    u64::try_from(now_millis() / 1000).unwrap_or(0)
}

/// The current time as a `SystemTime`, for APIs that take one.
pub fn now() -> SystemTime {
    UNIX_EPOCH + Duration::from_millis(u64::try_from(now_millis()).unwrap_or(0))
}

#[allow(clippy::disallowed_methods)]
fn system_millis() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis()
        .min(i64::MAX as u128) as i64
}

/// Holds the clock at a fixed time until dropped. Tests that fix the clock run one
/// at a time (the guard holds a lock), so they never see each other's time.
pub struct Fixed {
    _exclusive: MutexGuard<'static, ()>,
}

impl Fixed {
    /// Moves the fixed clock to another time.
    pub fn set(&self, millis: i64) {
        assert_ne!(millis, REAL, "reserved value");
        FIXED.store(millis, Ordering::SeqCst);
    }
    /// Moves the fixed clock forward.
    pub fn advance(&self, by: Duration) {
        let by = i64::try_from(by.as_millis()).unwrap_or(i64::MAX);
        FIXED.store(now_millis().saturating_add(by), Ordering::SeqCst);
    }
}

impl Drop for Fixed {
    fn drop(&mut self) {
        FIXED.store(REAL, Ordering::SeqCst);
    }
}

/// Fixes the clock at `millis` (since 1970) for the life of the returned guard.
pub fn fixed(millis: i64) -> Fixed {
    let exclusive = EXCLUSIVE
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let guard = Fixed {
        _exclusive: exclusive,
    };
    guard.set(millis);
    guard
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_fixed_clock_reads_back_advances_and_is_released() {
        {
            let clock = fixed(1_700_000_000_123);
            assert_eq!(now_millis(), 1_700_000_000_123);
            assert_eq!(now_secs(), 1_700_000_000);
            clock.advance(Duration::from_secs(90));
            assert_eq!(now_millis(), 1_700_000_090_123);
            assert_eq!(now(), UNIX_EPOCH + Duration::from_millis(1_700_000_090_123));
        }
        let real = now_millis();
        assert!(real > 1_700_000_000_000 && real != 1_700_000_090_123);
    }
}
