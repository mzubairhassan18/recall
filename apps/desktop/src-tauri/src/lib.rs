mod commands;
mod secret;
mod tray;

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};

use tauri::{AppHandle, Emitter, Manager};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Modifiers, Shortcut, ShortcutState};

pub struct AppState {
    pub smoke: bool,
    pub quitting: AtomicBool,
    pub user_data: PathBuf,
}

pub fn show_main(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

fn capture_shortcut() -> Shortcut {
    Shortcut::new(Some(Modifiers::CONTROL | Modifiers::SHIFT), Code::KeyR)
}

fn search_shortcut() -> Shortcut {
    Shortcut::new(Some(Modifiers::CONTROL | Modifiers::SHIFT), Code::KeyS)
}

pub fn run() {
    let smoke = std::env::args().any(|argument| argument == "--smoke");
    #[cfg(windows)]
    if smoke {
        // Release builds are GUI-subsystem binaries; reattach so `--smoke` can report.
        unsafe {
            windows_sys::Win32::System::Console::AttachConsole(
                windows_sys::Win32::System::Console::ATTACH_PARENT_PROCESS,
            );
        }
    }

    let user_data = commands::user_data_dir();
    let _ = std::fs::create_dir_all(&user_data);

    let mut builder = tauri::Builder::default();
    if !smoke {
        // A `--smoke` run must be deterministic: the single-instance plugin would otherwise
        // forward it to an already-running Recall and exit 0 without reporting anything.
        builder = builder.plugin(tauri_plugin_single_instance::init(|app, _argv, _cwd| {
            show_main(app);
        }));
    }

    builder
        .plugin(tauri_plugin_notification::init())
        .plugin(
            tauri_plugin_global_shortcut::Builder::new()
                .with_handler(|app, shortcut, event| {
                    if event.state() != ShortcutState::Pressed {
                        return;
                    }
                    if *shortcut == capture_shortcut() {
                        let _ = app.emit("recall:capture", ());
                    } else if *shortcut == search_shortcut() {
                        show_main(app);
                        let _ = app.emit("recall:focus-search", ());
                    }
                })
                .build(),
        )
        .manage(AppState {
            smoke,
            quitting: AtomicBool::new(false),
            user_data,
        })
        .invoke_handler(tauri::generate_handler![
            commands::app_paths,
            commands::fs_read,
            commands::fs_write,
            commands::fs_mkdir,
            commands::fs_rename,
            commands::secret_available,
            commands::secret_encrypt,
            commands::secret_decrypt,
            commands::clipboard_text,
            commands::clipboard_html,
            commands::clipboard_image,
            commands::clipboard_owner_app,
            commands::notify,
            commands::set_tray_tooltip,
            commands::open_external,
            commands::quit_app,
            commands::frontend_ready,
            commands::smoke_report,
        ])
        .setup(|app| {
            let handle = app.handle().clone();

            tray::create(&handle)?;

            if app.global_shortcut().register(capture_shortcut()).is_err() {
                eprintln!("[recall] could not register CommandOrControl+Shift+R; another app may own it");
            }
            if app.global_shortcut().register(search_shortcut()).is_err() {
                eprintln!("[recall] could not register CommandOrControl+Shift+S; another app may own it");
            }

            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                let state = window.state::<AppState>();
                if !state.quitting.load(Ordering::SeqCst) {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building Recall")
        .run(|_app, _event| {});
}
