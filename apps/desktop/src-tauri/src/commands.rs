use std::path::{Component, Path, PathBuf};

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use serde::Serialize;
use tauri::{AppHandle, State};

use crate::AppState;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AppPaths {
    pub user_data: String,
    pub smoke: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ClipboardImage {
    pub width: u32,
    pub height: u32,
    pub png_b64: String,
}

/** Electron used `app.getPath("userData")` = `%APPDATA%\<productName>`. */
pub fn user_data_dir() -> PathBuf {
    #[cfg(windows)]
    if let Some(appdata) = std::env::var_os("APPDATA") {
        return PathBuf::from(appdata).join("Recall");
    }
    if let Some(home) = std::env::var_os("HOME") {
        return PathBuf::from(home).join(".local/share/Recall");
    }
    std::env::temp_dir().join("Recall")
}

fn normalize(path: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for component in path.components() {
        match component {
            Component::ParentDir => {
                out.pop();
            }
            Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// Every absolute path the webview touches lives under the app data directory.
fn ensure_within(root: &Path, candidate: &str) -> Result<PathBuf, String> {
    let normalized = normalize(Path::new(candidate));
    if !normalized.starts_with(root) {
        return Err("forbidden-path".to_string());
    }
    Ok(normalized)
}

/* ------------------------------------------------------------------ */
/* paths + filesystem                                                  */
/* ------------------------------------------------------------------ */

#[tauri::command]
pub fn app_paths(state: State<'_, AppState>) -> AppPaths {
    AppPaths {
        user_data: state.user_data.to_string_lossy().into_owned(),
        smoke: state.smoke,
    }
}

#[tauri::command]
pub fn fs_read(state: State<'_, AppState>, path: String) -> Result<Option<String>, String> {
    let target = ensure_within(&state.user_data, &path)?;
    match std::fs::read(&target) {
        Ok(bytes) => Ok(Some(BASE64.encode(bytes))),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(_) => Err("read-failed".to_string()),
    }
}

#[tauri::command]
pub fn fs_write(state: State<'_, AppState>, path: String, data: String) -> Result<(), String> {
    let target = ensure_within(&state.user_data, &path)?;
    let bytes = BASE64.decode(data).map_err(|_| "invalid-data".to_string())?;
    if let Some(parent) = target.parent() {
        std::fs::create_dir_all(parent).map_err(|_| "write-failed".to_string())?;
    }
    std::fs::write(&target, bytes).map_err(|_| "write-failed".to_string())
}

#[tauri::command]
pub fn fs_mkdir(state: State<'_, AppState>, path: String) -> Result<(), String> {
    let target = ensure_within(&state.user_data, &path)?;
    std::fs::create_dir_all(&target).map_err(|_| "mkdir-failed".to_string())
}

#[tauri::command]
pub fn fs_rename(state: State<'_, AppState>, from: String, to: String) -> Result<(), String> {
    let source = ensure_within(&state.user_data, &from)?;
    let target = ensure_within(&state.user_data, &to)?;
    std::fs::rename(&source, &target).map_err(|_| "rename-failed".to_string())
}

/* ------------------------------------------------------------------ */
/* secrets (DPAPI)                                                     */
/* ------------------------------------------------------------------ */

#[tauri::command]
pub fn secret_available() -> bool {
    crate::secret::available()
}

#[tauri::command]
pub fn secret_encrypt(plaintext: String) -> Result<String, String> {
    crate::secret::encrypt(&plaintext)
}

#[tauri::command]
pub fn secret_decrypt(state: State<'_, AppState>, token: String) -> Result<String, String> {
    crate::secret::decrypt(&state.user_data, &token)
}

/* ------------------------------------------------------------------ */
/* clipboard                                                           */
/* ------------------------------------------------------------------ */

#[tauri::command]
pub fn clipboard_text() -> String {
    arboard::Clipboard::new()
        .ok()
        .and_then(|mut clipboard| clipboard.get_text().ok())
        .unwrap_or_default()
}

#[tauri::command]
pub fn clipboard_html() -> String {
    arboard::Clipboard::new()
        .ok()
        .and_then(|mut clipboard| clipboard.get().html().ok())
        .unwrap_or_default()
}

#[tauri::command]
pub fn clipboard_image() -> Option<ClipboardImage> {
    use image::ImageEncoder;

    let image = arboard::Clipboard::new()
        .ok()?
        .get_image()
        .ok()?;
    let width = image.width as u32;
    let height = image.height as u32;
    let rgba = image::RgbaImage::from_raw(width, height, image.bytes.into_owned())?;

    // Same rule as the Electron build: cap the long edge at 256 px, never upscale.
    let (target_width, target_height) = fit_within(width, height, 256);
    let resized = if (target_width, target_height) != (width, height) {
        image::imageops::resize(
            &rgba,
            target_width,
            target_height,
            image::imageops::FilterType::Triangle,
        )
    } else {
        rgba
    };

    let mut png = Vec::new();
    image::codecs::png::PngEncoder::new(&mut png)
        .write_image(
            resized.as_raw(),
            target_width,
            target_height,
            image::ExtendedColorType::Rgba8,
        )
        .ok()?;

    Some(ClipboardImage {
        width: target_width,
        height: target_height,
        png_b64: BASE64.encode(png),
    })
}

fn fit_within(width: u32, height: u32, max: u32) -> (u32, u32) {
    let longest = width.max(height);
    if longest <= max || longest == 0 {
        return (width, height);
    }
    let scale = max as f64 / longest as f64;
    (
        ((width as f64 * scale).round() as u32).max(1),
        ((height as f64 * scale).round() as u32).max(1),
    )
}

#[tauri::command]
pub fn clipboard_owner_app() -> Option<String> {
    #[cfg(windows)]
    {
        return windows_owner_app();
    }
    #[cfg(not(windows))]
    {
        None
    }
}

/// Same intent as the Electron PowerShell probe: name the process that owns the clipboard.
#[cfg(windows)]
fn windows_owner_app() -> Option<String> {
    use std::os::windows::ffi::OsStringExt;
    use windows_sys::Win32::Foundation::CloseHandle;
    use windows_sys::Win32::System::DataExchange::GetClipboardOwner;
    use windows_sys::Win32::System::Threading::{
        OpenProcess, QueryFullProcessImageNameW, PROCESS_QUERY_LIMITED_INFORMATION,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::GetWindowThreadProcessId;

    unsafe {
        let owner = GetClipboardOwner();
        if owner.is_null() {
            return None;
        }
        let mut pid: u32 = 0;
        GetWindowThreadProcessId(owner, &mut pid);
        if pid == 0 {
            return None;
        }
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
        if process.is_null() {
            return None;
        }
        let mut buffer = [0u16; 1024];
        let mut length = buffer.len() as u32;
        let ok = QueryFullProcessImageNameW(process, 0, buffer.as_mut_ptr(), &mut length);
        CloseHandle(process);
        if ok == 0 {
            return None;
        }
        let path = std::ffi::OsString::from_wide(&buffer[..length as usize]);
        Path::new(&path)
            .file_stem()
            .map(|stem| stem.to_string_lossy().into_owned())
    }
}

/* ------------------------------------------------------------------ */
/* shell                                                               */
/* ------------------------------------------------------------------ */

#[tauri::command]
pub fn notify(app: AppHandle, title: String, body: String) -> Result<(), String> {
    use tauri_plugin_notification::NotificationExt;
    app.notification()
        .builder()
        .title(title)
        .body(body)
        .show()
        .map_err(|_| "notify-failed".to_string())
}

#[tauri::command]
pub fn set_tray_tooltip(app: AppHandle, text: String) -> Result<(), String> {
    if let Some(tray) = app.tray_by_id(crate::tray::TRAY_ID) {
        tray.set_tooltip(Some(&text))
            .map_err(|_| "tray-failed".to_string())?;
    }
    Ok(())
}

#[tauri::command]
pub fn open_external(url: String) -> Result<(), String> {
    if !(url.starts_with("http://") || url.starts_with("https://")) {
        return Err("invalid-url".to_string());
    }
    open_in_browser(&url)
}

#[cfg(windows)]
fn open_in_browser(url: &str) -> Result<(), String> {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::UI::Shell::ShellExecuteW;
    use windows_sys::Win32::UI::WindowsAndMessaging::SW_SHOWNORMAL;

    let wide: Vec<u16> = std::ffi::OsStr::new(url)
        .encode_wide()
        .chain(std::iter::once(0))
        .collect();
    let verb: Vec<u16> = "open\0".encode_utf16().collect();
    let result = unsafe {
        ShellExecuteW(
            std::ptr::null_mut(),
            verb.as_ptr(),
            wide.as_ptr(),
            std::ptr::null(),
            std::ptr::null(),
            SW_SHOWNORMAL,
        )
    };
    if result as isize <= 32 {
        Err("open-failed".to_string())
    } else {
        Ok(())
    }
}

#[cfg(not(windows))]
fn open_in_browser(url: &str) -> Result<(), String> {
    std::process::Command::new("xdg-open")
        .arg(url)
        .spawn()
        .map(|_| ())
        .map_err(|_| "open-failed".to_string())
}

/* ------------------------------------------------------------------ */
/* lifecycle                                                           */
/* ------------------------------------------------------------------ */

#[tauri::command]
pub fn quit_app(app: AppHandle, state: State<'_, AppState>) {
    state
        .quitting
        .store(true, std::sync::atomic::Ordering::SeqCst);
    app.exit(0);
}

/// The webview has booted and the config is loaded: this replaces Electron's `ready-to-show`.
#[tauri::command]
pub fn frontend_ready(app: AppHandle) {
    crate::show_main(&app);
}

#[tauri::command]
pub fn smoke_report(app: AppHandle, state: State<'_, AppState>, ok: bool, message: String) {
    if state.smoke {
        // `app.exit` terminates without unwinding, so flush explicitly: redirected stdout is
        // block-buffered and would otherwise swallow the report.
        use std::io::Write;
        let mut out = std::io::stdout();
        let _ = writeln!(out, "[smoke] {message}");
        let _ = out.flush();
    }
    app.exit(if ok { 0 } else { 1 });
}
