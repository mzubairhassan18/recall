use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, Emitter, Manager};

pub const TRAY_ID: &str = "recall-tray";

/// Mirrors the Electron tray menu one-for-one: Open, Capture, Sync, Lock, Quit.
pub fn create(app: &AppHandle) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open Recall", true, None::<&str>)?;
    let capture = MenuItem::with_id(
        app,
        "capture",
        "Capture clipboard",
        true,
        Some("CmdOrCtrl+Shift+R"),
    )?;
    let sync = MenuItem::with_id(app, "sync", "Sync now", true, None::<&str>)?;
    let lock = MenuItem::with_id(app, "lock", "Lock", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit Recall", true, None::<&str>)?;

    let menu = Menu::with_items(
        app,
        &[
            &open,
            &capture,
            &sync,
            &PredefinedMenuItem::separator(app)?,
            &lock,
            &PredefinedMenuItem::separator(app)?,
            &quit,
        ],
    )?;

    let icon = tauri::image::Image::from_bytes(include_bytes!("../icons/tray.png"))?;

    TrayIconBuilder::with_id(TRAY_ID)
        .icon(icon)
        .tooltip("Recall")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => crate::show_main(app),
            "capture" => {
                let _ = app.emit("recall:capture", ());
            }
            "sync" => {
                let _ = app.emit("recall:tray-sync", ());
            }
            "lock" => {
                let _ = app.emit("recall:tray-lock", ());
            }
            "quit" => {
                app.state::<crate::AppState>()
                    .quitting
                    .store(true, std::sync::atomic::Ordering::SeqCst);
                app.exit(0);
            }
            _ => {}
        })
        .on_tray_icon_event(|tray, event| {
            if let TrayIconEvent::Click {
                button: MouseButton::Left,
                button_state: MouseButtonState::Up,
                ..
            } = event
            {
                crate::show_main(tray.app_handle());
            }
        })
        .build(app)?;

    Ok(())
}
