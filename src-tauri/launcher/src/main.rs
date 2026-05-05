//! audio-web sidecar launcher.
//!
//! This is a tiny shim that Tauri's `externalBin` machinery picks up as the
//! single sidecar entrypoint. Its job is to find the bundled python
//! interpreter (which lives next to itself in the resources directory) and
//! `exec` it with `-m audio_web.main`. All argv passes through unchanged.
//!
//! Why a launcher: python-build-standalone delivers a directory tree, not a
//! single executable. Tauri wants one executable to register as a sidecar.
//! This is the indirection.
//!
//! Two extra things the launcher does:
//!  - sets AUDIO_WEB_DIST to the bundled React assets if present, so the
//!    backend can mount /static at request time (same-origin model).
//!  - sets AUDIO_ROOT to a per-OS default if not already set.

use std::env;
use std::path::{Path, PathBuf};
use std::process::{Command, ExitCode};

fn main() -> ExitCode {
    // Tie the launcher and its child python into a Windows Job Object, with
    // KILL_ON_JOB_CLOSE set. When the launcher process is terminated (by
    // Tauri on app exit, by `cargo run` on hot-reload, by Task Manager…),
    // Windows automatically closes the job's last handle, which kills every
    // remaining process in the job — including the python child. Without
    // this, killing the launcher leaves an orphan python sitting on the
    // sidecar port, blocking the next dev iteration.
    #[cfg(windows)]
    let _job = setup_windows_job();

    let exe = env::current_exe().expect("could not locate launcher exe");
    let dir = exe
        .parent()
        .expect("launcher has no parent directory")
        .to_path_buf();

    let bundle = match locate_bundle(&dir) {
        Some(b) => b,
        None => {
            eprintln!(
                "launcher: could not locate python bundle dir (looked next to {dir:?}, in $AUDIO_BUNDLE_DIR, and walked up looking for src-tauri/binaries/python/)"
            );
            return ExitCode::from(127);
        }
    };
    let python = python_path(&bundle.dir);
    let dist_dir = bundle.dir.join("dist");

    // Diagnostics — gated on AUDIO_LAUNCHER_DEBUG=1 so production logs aren't
    // noisy. We still print spawn-failure errors below unconditionally, since
    // those are how the user discovers the launcher couldn't find python.
    let debug = env::var_os("AUDIO_LAUNCHER_DEBUG").is_some();
    if debug {
        eprintln!("launcher: exe={exe:?}");
        eprintln!(
            "launcher: bundle_dir={:?} (mode={:?})",
            bundle.dir, bundle.mode
        );
        eprintln!("launcher: python={python:?} exists={}", python.exists());
    }

    let app_root = match (env::var_os("AUDIO_ROOT"), &bundle) {
        (Some(_), _) => None, // user override wins
        (None, Bundle { mode: BundleMode::DevWalkup, repo_root: Some(r), .. }) => {
            // In dev (`cargo run`) the launcher landed in target/debug/. The
            // walk-up finder gave us the repo root — point AUDIO_ROOT there
            // so the sidecar reads the workspace's data/ instead of an empty
            // %APPDATA%/audio shadow tree.
            Some(r.clone())
        }
        _ => Some(guess_audio_root()),
    };

    let mut cmd = Command::new(&python);
    cmd.arg("-m").arg("audio_web.main");
    cmd.args(env::args().skip(1));

    if dist_dir.is_dir() {
        cmd.env("AUDIO_WEB_DIST", &dist_dir);
    }
    if let Some(root) = &app_root {
        cmd.env("AUDIO_ROOT", root);
        if debug {
            eprintln!("launcher: AUDIO_ROOT={root:?}");
        }
    }

    // PYTHONHOME / PYTHONPATH: we trust python-build-standalone's layout to
    // resolve site-packages relative to the python executable. Don't touch
    // these env vars; setting them wrong breaks site.py initialization.

    let status = match cmd.status() {
        Ok(s) => s,
        Err(e) => {
            eprintln!("launcher: failed to spawn {python:?}: {e}");
            return ExitCode::from(127);
        }
    };
    ExitCode::from(status.code().unwrap_or(1).clamp(0, 255) as u8)
}

#[derive(Debug)]
enum BundleMode {
    /// Bundle sits next to the launcher exe — production layout.
    Sibling,
    /// Bundle pointed at via $AUDIO_BUNDLE_DIR.
    EnvOverride,
    /// Bundle found by walking up looking for `src-tauri/binaries/python/`.
    /// This is the `cargo run` / `tauri dev` case.
    DevWalkup,
}

struct Bundle {
    dir: PathBuf,
    mode: BundleMode,
    /// Repo root, only known in DevWalkup mode (= bundle_dir's grandparent
    /// when found as `<repo>/src-tauri/binaries/`).
    repo_root: Option<PathBuf>,
}

/// Where the bundled python tree lives. Resolution order:
///
/// 1. `<launcher_dir>/python/` — production layout, where the build script
///    stages python next to the launcher exe (and the bundler ships it).
/// 2. `$AUDIO_BUNDLE_DIR/python/` — explicit override; useful for sysadmins
///    or test harnesses that want to run the launcher against an external tree.
/// 3. Walk up from the launcher dir looking for `src-tauri/binaries/python/`
///    or just `binaries/python/`. This is the dev fallback: in `cargo run`
///    Tauri stages the launcher to `target/debug/`, which has no sibling
///    python, but the bundle directory is one or two parents up.
fn locate_bundle(dir: &Path) -> Option<Bundle> {
    if python_path(dir).exists() {
        return Some(Bundle {
            dir: dir.to_path_buf(),
            mode: BundleMode::Sibling,
            repo_root: None,
        });
    }
    if let Some(env_dir) = env::var_os("AUDIO_BUNDLE_DIR") {
        let candidate = PathBuf::from(env_dir);
        if python_path(&candidate).exists() {
            return Some(Bundle {
                dir: candidate,
                mode: BundleMode::EnvOverride,
                repo_root: None,
            });
        }
    }
    let mut cursor: PathBuf = dir.to_path_buf();
    for _ in 0..6 {
        // `<repo>/src-tauri/binaries/python/` — the canonical dev layout.
        let sub = cursor.join("src-tauri").join("binaries");
        if python_path(&sub).exists() {
            return Some(Bundle {
                dir: sub,
                mode: BundleMode::DevWalkup,
                repo_root: Some(cursor.clone()),
            });
        }
        // `<here>/binaries/python/` — fires when cursor is already inside
        // src-tauri/. The repo root is then cursor's parent (i.e. the
        // directory that contains src-tauri/), NOT cursor itself.
        let sub2 = cursor.join("binaries");
        if python_path(&sub2).exists() {
            let repo_root = cursor.parent().map(|p| p.to_path_buf());
            return Some(Bundle {
                dir: sub2,
                mode: BundleMode::DevWalkup,
                repo_root,
            });
        }
        if !cursor.pop() {
            break;
        }
    }
    None
}

fn python_path(bundle_dir: &Path) -> PathBuf {
    if cfg!(windows) {
        bundle_dir.join("python").join("python.exe")
    } else {
        // Both macOS and Linux python-build-standalone tarballs put the
        // interpreter at python/bin/python3.<minor>. Use the unversioned
        // symlink that ships in the same dir.
        bundle_dir.join("python").join("bin").join("python3")
    }
}

/// Windows-only: create an unnamed Job Object, set KILL_ON_JOB_CLOSE, assign
/// the current process to it. The python child spawned later inherits into
/// the same job by default; when the job's last handle closes (i.e. when the
/// launcher exits, since the returned guard owns it), Windows kills every
/// still-running process in the job.
///
/// Failures are swallowed — the launcher continues without job protection.
/// The worst case is the same as today (orphaned python child on abnormal
/// launcher death), so silent fallback is correct.
#[cfg(windows)]
fn setup_windows_job() -> Option<win32job::Job> {
    let job = win32job::Job::create().ok()?;
    let mut info = job.query_extended_limit_info().ok()?;
    info.limit_kill_on_job_close();
    job.set_extended_limit_info(&info).ok()?;
    job.assign_current_process().ok()?;
    Some(job)
}

fn guess_audio_root() -> PathBuf {
    // Per-OS defaults that follow platform conventions. The user can override
    // with AUDIO_ROOT in the environment.
    if cfg!(windows) {
        // %APPDATA%\audio
        env::var_os("APPDATA")
            .map(|p| Path::new(&p).join("audio"))
            .unwrap_or_else(|| PathBuf::from("audio"))
    } else if cfg!(target_os = "macos") {
        // ~/Library/Application Support/audio
        env::var_os("HOME")
            .map(|h| {
                Path::new(&h)
                    .join("Library")
                    .join("Application Support")
                    .join("audio")
            })
            .unwrap_or_else(|| PathBuf::from("audio"))
    } else {
        // ~/.local/share/audio (XDG default), unless XDG_DATA_HOME is set
        if let Some(xdg) = env::var_os("XDG_DATA_HOME") {
            Path::new(&xdg).join("audio")
        } else if let Some(home) = env::var_os("HOME") {
            Path::new(&home).join(".local").join("share").join("audio")
        } else {
            PathBuf::from("audio")
        }
    }
}
