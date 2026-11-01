// Supervisor: process lifetime management (Windows Job Objects).
// Stub — full implementation in Task 4.

pub struct Supervisor {
    // TODO: worker handles, job object
}

impl Supervisor {
    pub fn new() -> Self {
        Self {}
    }
}

impl Default for Supervisor {
    fn default() -> Self {
        Self::new()
    }
}