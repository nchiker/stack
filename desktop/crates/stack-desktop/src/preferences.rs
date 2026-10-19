//! Derived view preferences in XDG config, read/written only by the repository worker.
use stack_core::Sort;
use std::{
    io::{Read, Write},
    os::unix::fs::{OpenOptionsExt, PermissionsExt},
    path::{Path, PathBuf},
};

pub fn path() -> Result<PathBuf, &'static str> {
    let base = if let Some(root) = std::env::var_os("XDG_CONFIG_HOME") {
        PathBuf::from(root)
    } else {
        PathBuf::from(std::env::var_os("HOME").ok_or("Set HOME or XDG_CONFIG_HOME")?)
            .join(".config")
    };
    if !base.is_absolute() {
        return Err("XDG config root must be absolute");
    }
    Ok(base.join(stack_core::APP_ID).join("view.json"))
}

pub fn load(path: &Path) -> Result<Sort, String> {
    if !path.exists() {
        return Ok(Sort::Newest);
    }
    let file = stack_archive::read_regular(path).map_err(|e| e.to_string())?;
    let mut data = String::new();
    file.take(128)
        .read_to_string(&mut data)
        .map_err(|e| e.to_string())?;
    let value: serde_json::Value = serde_json::from_str(&data).map_err(|e| e.to_string())?;
    match value["sort"].as_str() {
        Some("name") => Ok(Sort::Name),
        Some("newest") => Ok(Sort::Newest),
        _ => Err("Invalid saved sort preference".into()),
    }
}

pub fn save(path: &Path, sort: Sort) -> Result<(), String> {
    write(path, sort, load_grid(path).unwrap_or(true))
}

pub fn load_grid(path: &Path) -> Result<bool, String> {
    if !path.exists() {
        return Ok(true);
    }
    let file = stack_archive::read_regular(path).map_err(|e| e.to_string())?;
    let mut data = String::new();
    file.take(128)
        .read_to_string(&mut data)
        .map_err(|e| e.to_string())?;
    let value: serde_json::Value = serde_json::from_str(&data).map_err(|e| e.to_string())?;
    match value.get("grid") {
        None => Ok(true), // Existing sort-only preferences remain valid.
        Some(value) => value
            .as_bool()
            .ok_or_else(|| "Invalid saved layout preference".into()),
    }
}

pub fn save_grid(path: &Path, grid: bool) -> Result<(), String> {
    write(path, load(path).unwrap_or(Sort::Newest), grid)
}

fn write(path: &Path, sort: Sort, grid: bool) -> Result<(), String> {
    let run = || -> Result<(), Box<dyn std::error::Error>> {
        let parent = path.parent().ok_or("Invalid config path")?;
        std::fs::create_dir_all(parent)?;
        stack_archive::read_directory(parent)?;
        std::fs::set_permissions(parent, std::fs::Permissions::from_mode(0o700))?;
        if path.exists() {
            stack_archive::read_regular(path)?;
        }
        // Each worker owns a unique temporary name, including across processes.
        let temp = parent.join(format!(
            ".view-{}-{}.tmp",
            std::process::id(),
            stack_core::library::now()
        ));
        let mut file = std::fs::OpenOptions::new()
            .create_new(true)
            .write(true)
            .mode(0o600)
            .open(&temp)?;
        let result = || -> Result<(), Box<dyn std::error::Error>> {
            let sort = match sort {
                Sort::Newest => "newest",
                Sort::Name => "name",
            };
            file.write_all(
                serde_json::json!({"sort":sort,"grid":grid})
                    .to_string()
                    .as_bytes(),
            )?;
            file.sync_all()?;
            std::fs::rename(&temp, path)?;
            stack_archive::sync_directory(parent)?;
            Ok(())
        }();
        if result.is_err() {
            let _ = std::fs::remove_file(temp);
        }
        result
    };
    run().map_err(|e| format!("View preference could not be saved: {e}"))
}
