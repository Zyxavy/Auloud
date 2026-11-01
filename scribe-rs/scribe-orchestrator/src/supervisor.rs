// Process supervision: Windows Job Objects with KILL_ON_JOB_CLOSE.
// Ensures no orphaned Python/ffmpeg processes survive the orchestrator's death.

use std::io;
use tracing::info;

#[cfg(windows)]
use windows_sys::Win32::Foundation::{CloseHandle, HANDLE, INVALID_HANDLE_VALUE};
#[cfg(windows)]
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
};

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

    #[cfg(windows)]
    pub fn assign(&self, pid: u32) -> io::Result<()> {
        unsafe {
            let proc = windows_sys::Win32::System::Threading::OpenProcess(
                0x001FFFFF, // PROCESS_ALL_ACCESS
                0,
                pid,
            );
            if proc.is_null() {
                return Err(io::Error::last_os_error());
            }
            let ok = AssignProcessToJobObject(self.handle, proc);
            CloseHandle(proc);
            if ok == 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        }
    }

    #[cfg(not(windows))]
    pub fn assign(&self, _pid: u32) -> io::Result<()> {
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

/// Supervisor managing worker processes.
pub struct Supervisor {
    job: JobObject,
}

impl Supervisor {
    pub fn new() -> io::Result<Self> {
        let job = JobObject::new()?;
        Ok(Self { job })
    }

    pub fn assign_process(&self, pid: u32) -> io::Result<()> {
        self.job.assign(pid)
    }
}

impl Default for Supervisor {
    fn default() -> Self {
        Self::new().expect("failed to create supervisor")
    }
}