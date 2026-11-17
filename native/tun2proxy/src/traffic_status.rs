use crate::error::{Error, Result};
use std::os::raw::c_void;
use std::sync::{
    LazyLock, Mutex,
    atomic::{AtomicBool, AtomicU64, Ordering},
};

/// # Safety
///
/// set traffic status callback.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn tun2proxy_set_traffic_status_callback(
    send_interval_secs: u32,
    callback: Option<unsafe extern "C" fn(*const TrafficStatus, *mut c_void)>,
    ctx: *mut c_void,
) {
    let callback_registered = if let Ok(mut cb) = TRAFFIC_STATUS_CALLBACK.lock() {
        *cb = Some(TrafficStatusCallback(callback, ctx));
        true
    } else {
        log::error!("set traffic status callback failed");
        false
    };
    if send_interval_secs > 0 {
        SEND_INTERVAL_SECS.store(send_interval_secs as u64, std::sync::atomic::Ordering::Relaxed);
    }
    TRAFFIC_TX.store(0, Ordering::Relaxed);
    TRAFFIC_RX.store(0, Ordering::Relaxed);
    LAST_REPORT_SECONDS.store(TRAFFIC_CLOCK.elapsed().as_secs(), Ordering::Relaxed);
    CALLBACK_ACTIVE.store(callback_registered, Ordering::Release);
}

#[repr(C)]
#[derive(Debug, Default, Copy, Clone)]
pub struct TrafficStatus {
    pub tx: u64,
    pub rx: u64,
}

#[derive(Clone)]
struct TrafficStatusCallback(Option<unsafe extern "C" fn(*const TrafficStatus, *mut c_void)>, *mut c_void);

impl TrafficStatusCallback {
    unsafe fn call(self, info: &TrafficStatus) {
        if let Some(cb) = self.0 {
            unsafe { cb(info, self.1) };
        }
    }
}

unsafe impl Send for TrafficStatusCallback {}
unsafe impl Sync for TrafficStatusCallback {}

static TRAFFIC_STATUS_CALLBACK: Mutex<Option<TrafficStatusCallback>> = Mutex::new(None);
static SEND_INTERVAL_SECS: AtomicU64 = AtomicU64::new(1);
static CALLBACK_ACTIVE: AtomicBool = AtomicBool::new(false);
static TRAFFIC_TX: AtomicU64 = AtomicU64::new(0);
static TRAFFIC_RX: AtomicU64 = AtomicU64::new(0);
static LAST_REPORT_SECONDS: AtomicU64 = AtomicU64::new(0);
static TRAFFIC_CLOCK: LazyLock<std::time::Instant> = LazyLock::new(std::time::Instant::now);

pub(crate) fn traffic_status_update(delta_tx: usize, delta_rx: usize) -> Result<()> {
    if !CALLBACK_ACTIVE.load(Ordering::Relaxed) {
        return Ok(());
    }

    // The hot path is lock-free: many concurrent TCP flows can account bytes
    // without serializing on a process-wide mutex for every read operation.
    TRAFFIC_TX.fetch_add(delta_tx as u64, Ordering::Relaxed);
    TRAFFIC_RX.fetch_add(delta_rx as u64, Ordering::Relaxed);

    let now_seconds = TRAFFIC_CLOCK.elapsed().as_secs();
    let interval_secs = SEND_INTERVAL_SECS.load(Ordering::Relaxed).max(1);
    let last_report = LAST_REPORT_SECONDS.load(Ordering::Relaxed);
    if now_seconds.saturating_sub(last_report) >= interval_secs
        && LAST_REPORT_SECONDS
            .compare_exchange(last_report, now_seconds, Ordering::Relaxed, Ordering::Relaxed)
            .is_ok()
    {
        let traffic_status = TrafficStatus {
            tx: TRAFFIC_TX.load(Ordering::Relaxed),
            rx: TRAFFIC_RX.load(Ordering::Relaxed),
        };
        send_traffic_stat(&traffic_status)?;
    }
    Ok(())
}

fn send_traffic_stat(traffic_status: &TrafficStatus) -> Result<()> {
    if let Ok(cb) = TRAFFIC_STATUS_CALLBACK.lock()
        && let Some(cb) = cb.clone()
    {
        unsafe { cb.call(traffic_status) };
    }
    Ok(())
}
