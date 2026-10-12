// SPDX-License-Identifier: AGPL-3.0-or-later
//! Helper lifecycle state machine (Slice 17 AP1): pure transitions over
//! worker events, so every path is a unit test. The worker thread in
//! `helper.rs` does the I/O; this module decides what it means.

/// Auto-restarts after unexpected death before showing the crash screen.
pub const MAX_AUTO_RESTARTS: u32 = 3;

/// Lifecycle state (rendered by `main.rs`; see module docs for the shape).
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum State {
    /// Never started or failed to start (reason shown + Retry).
    Missing {
        /// Human reason (resolution steps included where known).
        reason: String,
    },
    /// Spawned, waiting for ready + health + version.
    Starting {
        /// Spawn attempt number (0-based).
        attempt: u32,
    },
    /// Verified serving helper.
    Running {
        /// Origin the client is bound to.
        base_url: String,
        /// Auto-restarts spent getting here.
        restarts: u32,
    },
    /// Died too often (manual Retry only).
    Crashed {
        /// Human reason.
        message: String,
    },
    /// Contract the client does not speak (upgrade screen).
    VersionMismatch {
        /// `api` integer the helper reported.
        have: u32,
        /// `api` integer this client speaks.
        want: u32,
    },
}

/// Worker/UI events driving the machine.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Event {
    /// Worker verified ready + health + version.
    Connected {
        /// Origin the client is bound to.
        base_url: String,
    },
    /// Startup failed (no exe, timeout, health refused).
    Failed {
        /// Human reason.
        message: String,
    },
    /// Contract the client does not speak.
    VersionWrong {
        /// `api` integer the helper reported.
        have: u32,
        /// `api` integer this client speaks.
        want: u32,
    },
    /// A running helper's process died.
    ChildDied,
    /// Retry button pressed.
    RetryRequested,
}

/// What the UI must do after a transition (spawning stays in `main.rs`).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    /// (Re)spawn the worker flow.
    Spawn,
    /// Nothing to do.
    Nothing,
}

/// Pure transition: `(next state, action)` for `(state, event)`.
pub fn transition(state: &State, event: &Event) -> (State, Action) {
    match (state, event) {
        (State::Starting { .. }, Event::Connected { base_url }) => (
            State::Running {
                base_url: base_url.clone(),
                restarts: 0,
            },
            Action::Nothing,
        ),
        (State::Starting { .. }, Event::Failed { message }) => (
            State::Missing {
                reason: message.clone(),
            },
            Action::Nothing,
        ),
        (State::Starting { .. }, Event::VersionWrong { have, want }) => (
            State::VersionMismatch {
                have: *have,
                want: *want,
            },
            Action::Nothing,
        ),
        // Died before verifying: startup failure, not a crash.
        (State::Starting { .. }, Event::ChildDied) => (
            State::Missing {
                reason: "helper exited before the ready line".to_owned(),
            },
            Action::Nothing,
        ),
        (State::Running { restarts, .. }, Event::ChildDied) => {
            if *restarts < MAX_AUTO_RESTARTS {
                (
                    State::Starting {
                        attempt: restarts + 1,
                    },
                    Action::Spawn,
                )
            } else {
                (
                    State::Crashed {
                        message: format!("helper died {restarts} times in a row; retry manually"),
                    },
                    Action::Nothing,
                )
            }
        }
        (
            State::Missing { .. } | State::Crashed { .. } | State::VersionMismatch { .. },
            Event::RetryRequested,
        ) => (State::Starting { attempt: 0 }, Action::Spawn),
        // Idempotent: repeats and out-of-order events change nothing.
        _ => (state.clone(), Action::Nothing),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn connect_beats_start() {
        let (state, action) = transition(
            &State::Starting { attempt: 0 },
            &Event::Connected {
                base_url: "http://127.0.0.1:1".to_owned(),
            },
        );
        assert_eq!(
            state,
            State::Running {
                base_url: "http://127.0.0.1:1".to_owned(),
                restarts: 0
            }
        );
        assert_eq!(action, Action::Nothing);
    }

    #[test]
    fn startup_failure_returns_to_missing() {
        let (state, action) = transition(
            &State::Starting { attempt: 0 },
            &Event::Failed {
                message: "no helper found".to_owned(),
            },
        );
        assert!(matches!(state, State::Missing { .. }));
        assert_eq!(action, Action::Nothing);
    }

    #[test]
    fn version_mismatch_screens() {
        let (state, _) = transition(
            &State::Starting { attempt: 0 },
            &Event::VersionWrong { have: 2, want: 1 },
        );
        assert_eq!(state, State::VersionMismatch { have: 2, want: 1 });
    }

    #[test]
    fn death_before_ready_is_startup_failure() {
        let (state, _) = transition(&State::Starting { attempt: 0 }, &Event::ChildDied);
        assert!(matches!(state, State::Missing { .. }));
    }

    #[test]
    fn running_death_restarts_then_crashes() {
        let running = State::Running {
            base_url: "http://127.0.0.1:1".to_owned(),
            restarts: 0,
        };
        let (state, action) = transition(&running, &Event::ChildDied);
        assert_eq!(state, State::Starting { attempt: 1 });
        assert_eq!(action, Action::Spawn);
        let running = State::Running {
            base_url: "http://127.0.0.1:1".to_owned(),
            restarts: MAX_AUTO_RESTARTS,
        };
        let (state, action) = transition(&running, &Event::ChildDied);
        assert!(matches!(state, State::Crashed { .. }));
        assert_eq!(action, Action::Nothing);
    }

    #[test]
    fn retry_respawns_from_every_halt() {
        for halt in [
            State::Missing {
                reason: "x".to_owned(),
            },
            State::Crashed {
                message: "x".to_owned(),
            },
            State::VersionMismatch { have: 9, want: 1 },
        ] {
            let (state, action) = transition(&halt, &Event::RetryRequested);
            assert_eq!(state, State::Starting { attempt: 0 });
            assert_eq!(action, Action::Spawn);
        }
    }

    #[test]
    fn repeats_change_nothing() {
        let running = State::Running {
            base_url: "http://127.0.0.1:1".to_owned(),
            restarts: 0,
        };
        let (state, action) = transition(
            &running,
            &Event::Connected {
                base_url: "http://127.0.0.1:1".to_owned(),
            },
        );
        assert_eq!(state, running);
        assert_eq!(action, Action::Nothing);
    }
}
