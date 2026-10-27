use std::path::Path;

use aes_gcm::aead::{Aead, KeyInit, Payload};
use aes_gcm::{Aes256Gcm, Nonce};
use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;

/**
 * Secret storage for the relay secret. New values are wrapped with Windows DPAPI, the same
 * primitive Electron's safeStorage used. Values written by the previous Electron build carry
 * Chromium's `v10` AES-256-GCM envelope; those are still readable so an existing install keeps
 * its relay credentials after the upgrade.
 */

pub fn available() -> bool {
    cfg!(windows)
}

pub fn encrypt(plaintext: &str) -> Result<String, String> {
    protect(plaintext.as_bytes()).map(|blob| BASE64.encode(blob))
}

pub fn decrypt(user_data: &Path, token: &str) -> Result<String, String> {
    let blob = BASE64
        .decode(token)
        .map_err(|_| "decrypt-failed".to_string())?;
    if blob.starts_with(b"v10") || blob.starts_with(b"v11") {
        return decrypt_chromium(user_data, &blob);
    }
    let plain = unprotect(&blob)?;
    String::from_utf8(plain).map_err(|_| "decrypt-failed".to_string())
}

fn decrypt_chromium(user_data: &Path, blob: &[u8]) -> Result<String, String> {
    if blob.len() < 15 {
        return Err("decrypt-failed".to_string());
    }
    let key = chromium_key(user_data).ok_or_else(|| "decrypt-failed".to_string())?;
    let cipher = Aes256Gcm::new_from_slice(&key).map_err(|_| "decrypt-failed".to_string())?;
    let plain = cipher
        .decrypt(
            Nonce::from_slice(&blob[3..15]),
            Payload {
                msg: &blob[15..],
                aad: b"",
            },
        )
        .map_err(|_| "decrypt-failed".to_string())?;
    String::from_utf8(plain).map_err(|_| "decrypt-failed".to_string())
}

fn chromium_key(user_data: &Path) -> Option<[u8; 32]> {
    let local_state = std::fs::read_to_string(user_data.join("Local State")).ok()?;
    let value: serde_json::Value = serde_json::from_str(&local_state).ok()?;
    let encoded = value.get("os_crypt")?.get("encrypted_key")?.as_str()?;
    let wrapped = BASE64.decode(encoded).ok()?;
    let wrapped = wrapped.strip_prefix(b"DPAPI").unwrap_or(&wrapped);
    let raw = unprotect(wrapped).ok()?;
    if raw.len() != 32 {
        return None;
    }
    let mut key = [0u8; 32];
    key.copy_from_slice(&raw);
    Some(key)
}

#[cfg(windows)]
fn protect(data: &[u8]) -> Result<Vec<u8>, String> {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CryptProtectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    unsafe {
        let mut input = CRYPT_INTEGER_BLOB {
            cbData: data.len() as u32,
            pbData: data.as_ptr() as *mut u8,
        };
        let mut output = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: std::ptr::null_mut(),
        };
        let ok = CryptProtectData(
            &mut input,
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        );
        if ok == 0 {
            return Err("encrypt-failed".to_string());
        }
        let bytes = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData as *mut _);
        Ok(bytes)
    }
}

#[cfg(windows)]
fn unprotect(data: &[u8]) -> Result<Vec<u8>, String> {
    use windows_sys::Win32::Foundation::LocalFree;
    use windows_sys::Win32::Security::Cryptography::{
        CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    unsafe {
        let mut input = CRYPT_INTEGER_BLOB {
            cbData: data.len() as u32,
            pbData: data.as_ptr() as *mut u8,
        };
        let mut output = CRYPT_INTEGER_BLOB {
            cbData: 0,
            pbData: std::ptr::null_mut(),
        };
        let ok = CryptUnprotectData(
            &mut input,
            std::ptr::null_mut(),
            std::ptr::null(),
            std::ptr::null(),
            std::ptr::null(),
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        );
        if ok == 0 {
            return Err("decrypt-failed".to_string());
        }
        let bytes = std::slice::from_raw_parts(output.pbData, output.cbData as usize).to_vec();
        LocalFree(output.pbData as *mut _);
        Ok(bytes)
    }
}

#[cfg(not(windows))]
fn protect(_data: &[u8]) -> Result<Vec<u8>, String> {
    Err("encrypt-unavailable".to_string())
}

#[cfg(not(windows))]
fn unprotect(_data: &[u8]) -> Result<Vec<u8>, String> {
    Err("decrypt-failed".to_string())
}
