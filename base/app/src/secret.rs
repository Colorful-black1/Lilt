//! API key storage. Windows uses DPAPI (the same user-bound protection as
//! Electron's safeStorage); other platforms fall back to a user-only file.

use std::fs;
use std::path::PathBuf;

pub fn key_path() -> PathBuf {
    crate::library::data_dir().join("ai-key.bin")
}

pub fn has_api_key() -> bool {
    load_api_key().is_some()
}

pub fn save_api_key(key: &str) -> Result<(), String> {
    let key = key.trim();
    if key.is_empty() {
        return Err("API Key 不能为空".to_owned());
    }
    let path = key_path();
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| format!("无法创建配置目录：{error}"))?;
    }
    let bytes = protect(key.as_bytes())?;
    fs::write(&path, bytes).map_err(|error| format!("无法保存 API Key：{error}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&path, fs::Permissions::from_mode(0o600));
    }
    Ok(())
}

pub fn load_api_key() -> Option<String> {
    let bytes = fs::read(key_path()).ok()?;
    if bytes.is_empty() {
        return None;
    }
    let plain = unprotect(&bytes).ok()?;
    let text = String::from_utf8(plain).ok()?;
    let text = text.trim().to_owned();
    (!text.is_empty()).then_some(text)
}

pub fn clear_api_key() {
    let _ = fs::remove_file(key_path());
}

#[cfg(target_os = "windows")]
fn protect(bytes: &[u8]) -> Result<Vec<u8>, String> {
    use windows::Win32::Foundation::LocalFree;
    use windows::Win32::Security::Cryptography::{
        CryptProtectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    unsafe {
        CryptProtectData(
            &input,
            windows::core::PCWSTR::null(),
            None,
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
        .map_err(|error| format!("DPAPI 加密失败：{error}"))?;
        let slice = std::slice::from_raw_parts(output.pbData, output.cbData as usize);
        let result = slice.to_vec();
        let _ = LocalFree(Some(windows::Win32::Foundation::HLOCAL(
            output.pbData as *mut core::ffi::c_void,
        )));
        Ok(result)
    }
}

#[cfg(target_os = "windows")]
fn unprotect(bytes: &[u8]) -> Result<Vec<u8>, String> {
    use windows::Win32::Foundation::LocalFree;
    use windows::Win32::Security::Cryptography::{
        CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    if bytes.is_empty() {
        return Ok(Vec::new());
    }
    let input = CRYPT_INTEGER_BLOB {
        cbData: bytes.len() as u32,
        pbData: bytes.as_ptr() as *mut u8,
    };
    let mut output = CRYPT_INTEGER_BLOB::default();
    unsafe {
        CryptUnprotectData(
            &input,
            None,
            None,
            None,
            None,
            CRYPTPROTECT_UI_FORBIDDEN,
            &mut output,
        )
        .map_err(|error| format!("DPAPI 解密失败：{error}"))?;
        let slice = std::slice::from_raw_parts(output.pbData, output.cbData as usize);
        let result = slice.to_vec();
        let _ = LocalFree(Some(windows::Win32::Foundation::HLOCAL(
            output.pbData as *mut core::ffi::c_void,
        )));
        Ok(result)
    }
}

#[cfg(not(target_os = "windows"))]
fn protect(bytes: &[u8]) -> Result<Vec<u8>, String> {
    // Keep the file private; the platform keychain is not available in this
    // build. The settings UI states that the key is stored locally.
    Ok(bytes.to_vec())
}

#[cfg(not(target_os = "windows"))]
fn unprotect(bytes: &[u8]) -> Result<Vec<u8>, String> {
    Ok(bytes.to_vec())
}

#[cfg(all(test, target_os = "windows"))]
mod tests {
    use super::{protect, unprotect};

    #[test]
    fn dpapi_roundtrip() {
        let key = b"sk-test-1234";
        let encrypted = protect(key).unwrap();
        assert_ne!(encrypted, key);
        assert_eq!(unprotect(&encrypted).unwrap(), key);
    }
}
