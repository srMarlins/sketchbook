//! Audio app — native shell.
//!
//! Boots the FastAPI sidecar (`audio-web`), waits for it to print its bound
//! port on stdout, then loads the React UI from that port into a webview.
//!
//! Sidecar contract is documented in `packages/web/audio_web/main.py`. The
//! key protocol message is the line `AUDIO_WEB_PORT=<n>` printed once on
//! sidecar startup; the shell parses this to discover where to point the
//! webview.

use std::sync::Mutex;
use std::time::Duration;

use serde::Serialize;
use tauri::menu::{Menu, MenuBuilder, SubmenuBuilder};
use tauri::{AppHandle, Emitter, Manager, RunEvent, WebviewUrl, WebviewWindowBuilder};
use tauri_plugin_shell::process::{CommandChild, CommandEvent};
use tauri_plugin_shell::ShellExt;

const SIDECAR_NAME: &str = "audio-web";
const PORT_LINE_PREFIX: &str = "AUDIO_WEB_PORT=";
const SIDECAR_BOOT_TIMEOUT_SECS: u64 = 15;

/// Shared handle to the running sidecar so we can kill it on app exit.
#[derive(Default)]
struct Sidecar {
    child: Mutex<Option<CommandChild>>,
}

#[derive(Clone, Serialize)]
struct BootProgress {
    stage: &'static str,
    detail: String,
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    // Default to info level so sidecar stderr (warn) and lifecycle (info) show
    // up without callers needing to set RUST_LOG. Callers can still override.
    env_logger::Builder::from_env(env_logger::Env::default().default_filter_or("info")).init();

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .plugin(tauri_plugin_dialog::init())
        .manage(Sidecar::default())
        .setup(|app| {
            // Native menu — stock items only for v1. The platform default
            // menus (Edit clipboard verbs on macOS, etc.) come from the
            // SubmenuBuilder helpers.
            let menu = build_menu(app.handle())?;
            app.set_menu(menu)?;

            let handle = app.handle().clone();
            // Spawn the sidecar boot off the main thread so the splash window
            // can render immediately. The boot routine swaps the splash for
            // the real UI window once the port handshake completes.
            tauri::async_runtime::spawn(async move {
                if let Err(e) = boot_sidecar_and_open_window(handle.clone()).await {
                    log::error!("sidecar boot failed: {e}");
                    let _ = handle.emit(
                        "sidecar-boot-failed",
                        BootProgress {
                            stage: "failed",
                            detail: e,
                        },
                    );
                }
            });
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if let RunEvent::ExitRequested { .. } | RunEvent::Exit = event {
                kill_sidecar(app);
            }
        });
}

/// Spawn the sidecar, wait for the port handshake, then open the main window
/// pointed at the sidecar's URL. Closes the splash window on success.
async fn boot_sidecar_and_open_window(app: AppHandle) -> Result<(), String> {
    let _ = app.emit(
        "sidecar-progress",
        BootProgress {
            stage: "spawning",
            detail: "starting backend".into(),
        },
    );

    // sidecar() resolves to the bundled binary in release, or the dev path
    // (../target/debug/<name>) in dev. Configured via tauri.conf.json's
    // `bundle.externalBin`.
    //
    // We set AUDIO_BUNDLE_DIR to the resource_dir() so the launcher can find
    // the bundled python+dist in production (where they're staged via
    // tauri.conf.json's `bundle.resources` rather than as siblings of the
    // launcher exe). In dev this var is harmless — the launcher's walk-up
    // resolution would have found the bundle anyway, and EnvOverride takes
    // precedence only when the path actually contains a python/ tree.
    let mut cmd = app
        .shell()
        .sidecar(SIDECAR_NAME)
        .map_err(|e| format!("could not locate sidecar: {e}"))?
        .args(["--port", "0"]);
    if let Ok(resource_dir) = app.path().resource_dir() {
        cmd = cmd.env("AUDIO_BUNDLE_DIR", resource_dir.to_string_lossy().to_string());
    }

    let (mut rx, child) = cmd
        .spawn()
        .map_err(|e| format!("could not spawn sidecar: {e}"))?;

    {
        let state: tauri::State<'_, Sidecar> = app.state();
        let mut guard = state.child.lock().unwrap();
        *guard = Some(child);
    }

    let port = tokio::time::timeout(
        Duration::from_secs(SIDECAR_BOOT_TIMEOUT_SECS),
        wait_for_port(&mut rx, &app),
    )
    .await
    .map_err(|_| {
        format!(
            "backend did not announce port within {}s",
            SIDECAR_BOOT_TIMEOUT_SECS
        )
    })??;

    let url = format!("http://127.0.0.1:{port}");
    log::info!("sidecar ready at {url}");

    // Drain remaining sidecar output to logs in the background. If the
    // sidecar dies later, we surface that to the UI as `sidecar-died`.
    let app_for_drain = app.clone();
    tauri::async_runtime::spawn(async move {
        while let Some(event) = rx.recv().await {
            match event {
                CommandEvent::Stdout(buf) => {
                    log::info!("sidecar: {}", String::from_utf8_lossy(&buf).trim_end());
                }
                CommandEvent::Stderr(buf) => {
                    log::warn!("sidecar: {}", String::from_utf8_lossy(&buf).trim_end());
                }
                CommandEvent::Terminated(payload) => {
                    log::error!("sidecar terminated: code={:?}", payload.code);
                    let _ = app_for_drain.emit(
                        "sidecar-died",
                        BootProgress {
                            stage: "died",
                            detail: format!("backend exited with code {:?}", payload.code),
                        },
                    );
                    break;
                }
                _ => {}
            }
        }
    });

    let _ = app.emit(
        "sidecar-progress",
        BootProgress {
            stage: "ready",
            detail: url.clone(),
        },
    );

    // Replace the splash with the real UI window. Building a new window is
    // simpler than calling navigate() on an existing one because of how
    // Tauri handles webview URL changes across platforms.
    let main_window = WebviewWindowBuilder::new(&app, "main", WebviewUrl::External(url.parse().map_err(|e| format!("bad url: {e}"))?))
        .title("audio")
        .inner_size(1280.0, 800.0)
        .min_inner_size(900.0, 600.0)
        .maximized(true)
        .build()
        .map_err(|e| format!("could not open main window: {e}"))?;

    if let Some(splash) = app.get_webview_window("splash") {
        let _ = splash.close();
    }
    let _ = main_window.set_focus();
    Ok(())
}

/// Read sidecar stdout looking for the `AUDIO_WEB_PORT=<n>` handshake line.
/// Forwards intermediate stdout/stderr to the log so failures are debuggable.
async fn wait_for_port(
    rx: &mut tokio::sync::mpsc::Receiver<CommandEvent>,
    app: &AppHandle,
) -> Result<u16, String> {
    while let Some(event) = rx.recv().await {
        match event {
            CommandEvent::Stdout(buf) => {
                let line = String::from_utf8_lossy(&buf);
                for l in line.lines() {
                    log::info!("sidecar: {l}");
                    if let Some(rest) = l.strip_prefix(PORT_LINE_PREFIX) {
                        return rest
                            .trim()
                            .parse::<u16>()
                            .map_err(|e| format!("malformed port handshake {l:?}: {e}"));
                    }
                }
            }
            CommandEvent::Stderr(buf) => {
                let line = String::from_utf8_lossy(&buf);
                for l in line.lines() {
                    log::warn!("sidecar: {l}");
                    let _ = app.emit(
                        "sidecar-progress",
                        BootProgress {
                            stage: "starting",
                            detail: l.to_string(),
                        },
                    );
                }
            }
            CommandEvent::Terminated(payload) => {
                return Err(format!(
                    "sidecar exited before announcing port (code={:?})",
                    payload.code
                ));
            }
            _ => {}
        }
    }
    Err("sidecar stdout closed before announcing port".into())
}

/// Best-effort sidecar shutdown on app exit.
///
/// `child.kill()` calls TerminateProcess on Windows, which is brutal — uvicorn
/// gets no chance to flush logs or close handlers. The launcher has a Windows
/// Job Object set up (KILL_ON_JOB_CLOSE), so this also reaps the python child.
///
/// SQLite WAL recovery handles any half-flushed write on the next open, so
/// we accept the brutality. The only loss is that the `data/logs/audio.log`
/// rotating handler may drop the last few buffered lines — acceptable.
///
/// Idempotent: `guard.take()` returns Some at most once, so calling this from
/// both `RunEvent::ExitRequested` and `RunEvent::Exit` is safe.
fn kill_sidecar(app: &AppHandle) {
    let state: tauri::State<'_, Sidecar> = app.state();
    let mut guard = state.child.lock().unwrap();
    if let Some(child) = guard.take() {
        log::info!("shutting down sidecar (pid={})", child.pid());
        if let Err(e) = child.kill() {
            log::warn!("sidecar kill failed: {e}");
        }
    }
}

// Wrap the inner Mutex usage so callers don't need to import std::sync.
impl Sidecar {
    #[allow(dead_code)]
    fn set(&self, child: CommandChild) {
        let mut g = self.child.lock().unwrap();
        *g = Some(child);
    }
}

/// Build the native menu bar. v1 keeps it minimal — File→Quit, Edit clipboard
/// verbs, View→Reload, Help→About. Native conventions per platform are
/// handled by Tauri's stock-item helpers.
fn build_menu(app: &AppHandle) -> tauri::Result<Menu<tauri::Wry>> {
    use tauri::menu::PredefinedMenuItem;

    let file = SubmenuBuilder::new(app, "File")
        .item(&PredefinedMenuItem::quit(app, Some("Quit"))?)
        .build()?;

    let edit = SubmenuBuilder::new(app, "Edit")
        .item(&PredefinedMenuItem::undo(app, None)?)
        .item(&PredefinedMenuItem::redo(app, None)?)
        .separator()
        .item(&PredefinedMenuItem::cut(app, None)?)
        .item(&PredefinedMenuItem::copy(app, None)?)
        .item(&PredefinedMenuItem::paste(app, None)?)
        .item(&PredefinedMenuItem::select_all(app, None)?)
        .build()?;

    let view = SubmenuBuilder::new(app, "View")
        .item(&PredefinedMenuItem::fullscreen(app, None)?)
        .build()?;

    let help = SubmenuBuilder::new(app, "Help")
        .item(&PredefinedMenuItem::about(app, Some("About audio"), None)?)
        .build()?;

    MenuBuilder::new(app)
        .items(&[&file, &edit, &view, &help])
        .build()
}
