// Process supervision: Windows Job Objects with KILL_ON_JOB_CLOSE.
// Ensures no orphaned Python/ffmpeg processes survive the orchestrator's death.
// Children inherit the job, so the orchestrator joins it once, before it spawns any.

use std::io;
use tracing::{info, warn};

#[cfg(windows)]
use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE};
#[cfg(windows)]
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};
#[cfg(windows)]
use windows_sys::Win32::System::Threading::GetCurrentProcess;

/// A Job Object that kills all assigned processes when closed.
pub struct JobObject {
    #[cfg(windows)]
    handle: HANDLE,
}

impl JobObject {
    #[cfg(windows)]
    pub fn new() -> io::Result<Self> {
        unsafe {
            let handle = CreateJobObjectW(std::ptr::null(), std::ptr::null());
            if handle == INVALID_HANDLE_VALUE || handle.is_null() {
                return Err(io::Error::last_os_error());
            }
            let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
            info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
            let ok = SetInformationJobObject(
                handle,
                JobObjectExtendedLimitInformation,
                &info as *const _ as *const _,
                std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
            );
            if ok == 0 {
                CloseHandle(handle);
                return Err(io::Error::last_os_error());
            }
            info!("created Job Object with KILL_ON_JOB_CLOSE");
            Ok(Self { handle })
        }
    }

    #[cfg(not(windows))]
    pub fn new() -> io::Result<Self> {
        Ok(Self {})
    }

    /// Adds this process to the job. Done before spawning, so children inherit it.
    #[cfg(windows)]
    pub fn assign_current_process(&self) -> io::Result<()> {
        unsafe {
            if AssignProcessToJobObject(self.handle, GetCurrentProcess()) == 0 {
                return Err(io::Error::last_os_error());
            }
        }
        Ok(())
    }

    #[cfg(not(windows))]
    pub fn assign_current_process(&self) -> io::Result<()> {
        Ok(())
    }
}

impl Drop for JobObject {
    #[cfg(windows)]
    fn drop(&mut self) {
        unsafe {
            CloseHandle(self.handle);
        }
        info!("Job Object closed; all assigned processes killed");
    }

    #[cfg(not(windows))]
    fn drop(&mut self) {}
}

/// Supervisor managing worker processes. Keep it alive for the whole run.
pub struct Supervisor {
    _job: JobObject,
}

impl Supervisor {
    /// Creates the job and joins it. Call before spawning children.
    pub fn new() -> io::Result<Self> {
        let job = JobObject::new()?;
        if let Err(e) = job.assign_current_process() {
            warn!("orchestrator is not in its Job Object, workers may outlive it: {e}");
        }
        Ok(Self { _job: job })
    }
}
