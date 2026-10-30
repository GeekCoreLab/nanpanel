//! Managed backups: a tar.gz per run under a root-owned directory, named
//! `<name>-<UTC stamp>.tar.gz`, with the SHA-256 and size reported back so the panel can show
//! exactly what it created. Only files matching that shape inside the managed directory are
//! ever listed or deleted.

use std::{
    fs,
    path::{Path, PathBuf},
    process::Command,
    time::SystemTime,
};

use anyhow::{Context, bail};
use serde::Deserialize;
use sha2::{Digest, Sha256};

const MAX_SOURCE_BYTES: usize = 1024;
const MAX_NAME_LEN: usize = 32;

#[derive(Clone, Debug)]
pub struct BackupConfig {
    directory: PathBuf,
    tar: PathBuf,
}

#[derive(Debug, Deserialize)]
struct BackupPayload {
    source: String,
    name: String,
}

impl BackupConfig {
    pub fn from_environment() -> anyhow::Result<Option<Self>> {
        let Some(directory) = std::env::var_os("NANPANEL_BACKUP_DIR") else {
            return Ok(None);
        };
        let directory = PathBuf::from(directory);
        if !directory.is_absolute() {
            bail!("NANPANEL_BACKUP_DIR must be an absolute path");
        }
        let directory = fs::canonicalize(&directory)
            .with_context(|| format!("resolve backup directory {}", directory.display()))?;
        if !fs::metadata(&directory)
            .context("inspect backup directory")?
            .is_dir()
        {
            bail!("backup directory must be a directory");
        }
        let tar =
            crate::trusted_executable(&["/usr/bin/tar", "/bin/tar"]).map_err(anyhow::Error::msg)?;
        Ok(Some(Self { directory, tar }))
    }

    #[cfg(test)]
    pub fn at(directory: &Path, tar: &Path) -> Self {
        Self {
            directory: directory.to_path_buf(),
            tar: tar.to_path_buf(),
        }
    }

    /// Backup file names are `<name>-<8 digits>T<6 digits>Z.tar.gz`.
    pub fn validate_target(target: &str) -> bool {
        let Some(stem) = target.strip_suffix(".tar.gz") else {
            return false;
        };
        let Some((name, stamp)) = stem.rsplit_once('-') else {
            return false;
        };
        valid_name(name)
            && stamp.len() == 16
            && stamp.as_bytes()[8] == b'T'
            && stamp.as_bytes()[15] == b'Z'
            && stamp
                .bytes()
                .enumerate()
                .all(|(index, byte)| index == 8 || index == 15 || byte.is_ascii_digit())
    }

    pub fn create(&self, payload_json: Option<&str>) -> Result<String, String> {
        let Some(payload_json) = payload_json else {
            return Err("DENIED: backup payload is required".to_owned());
        };
        let payload: BackupPayload = serde_json::from_str(payload_json)
            .map_err(|_| "DENIED: backup payload schema is invalid".to_owned())?;
        if !valid_name(&payload.name) {
            return Err(
                "DENIED: backup name must be 2-32 lowercase alphanumerics or dashes".to_owned(),
            );
        }
        if payload.source.len() > MAX_SOURCE_BYTES || !payload.source.starts_with('/') {
            return Err("DENIED: backup source must be an absolute path".to_owned());
        }
        let source = fs::canonicalize(&payload.source)
            .map_err(|_| "DENIED: backup source does not exist".to_owned())?;
        if source == Path::new("/") {
            return Err("DENIED: refusing to back up the whole filesystem".to_owned());
        }
        if source.starts_with(&self.directory) || self.directory.starts_with(&source) {
            return Err("DENIED: backup source overlaps the backup directory".to_owned());
        }

        let file = format!("{}-{}.tar.gz", payload.name, utc_stamp());
        let path = self.directory.join(&file);
        if path.exists() {
            // Two runs inside the same second must not overwrite each other.
            return Err(
                "DENIED: a backup with the same name already exists this second".to_owned(),
            );
        }
        let temporary = self.directory.join(format!(".{file}.tmp"));
        let parent = source
            .parent()
            .ok_or("DENIED: backup source has no parent directory")?;
        let basename = source
            .file_name()
            .ok_or("DENIED: backup source has no name")?;

        let output = Command::new(&self.tar)
            .arg("-czf")
            .arg(&temporary)
            .arg("-C")
            .arg(parent)
            .arg(basename)
            .output()
            .map_err(|_| "DENIED: tar could not be executed".to_owned())?;
        if !output.status.success() {
            let _ = fs::remove_file(&temporary);
            return Err(format!(
                "DENIED: tar failed: {}",
                String::from_utf8_lossy(&output.stderr).trim()
            ));
        }
        fs::rename(&temporary, &path)
            .map_err(|_| "DENIED: backup file could not be installed".to_owned())?;

        let size = fs::metadata(&path)
            .map_err(|_| "DENIED: backup file is unreadable after writing".to_owned())?
            .len();
        let sha256 = sha256_of(&path)?;
        Ok(format!(
            "{{\"file\":\"{}\",\"source\":\"{}\",\"sizeBytes\":{},\"sha256\":\"{}\"}}",
            file,
            source.display(),
            size,
            sha256
        ))
    }

    pub fn list(&self) -> Result<String, String> {
        let entries = fs::read_dir(&self.directory)
            .map_err(|_| "DENIED: backup directory cannot be read".to_owned())?;
        let mut files: Vec<(String, u64, u128)> = Vec::new();
        for entry in entries.flatten() {
            let file = entry.file_name().to_string_lossy().into_owned();
            if !Self::validate_target(&file) {
                continue;
            }
            let Ok(metadata) = entry.metadata() else {
                continue;
            };
            let created = metadata
                .modified()
                .ok()
                .and_then(|time| time.duration_since(SystemTime::UNIX_EPOCH).ok())
                .map(|duration| duration.as_millis())
                .unwrap_or(0);
            files.push((file, metadata.len(), created));
        }
        files.sort_by(|left, right| right.0.cmp(&left.0));
        let items = files
            .iter()
            .map(|(file, size, created)| {
                format!("{{\"file\":\"{file}\",\"sizeBytes\":{size},\"createdAt\":{created}}}")
            })
            .collect::<Vec<_>>()
            .join(",");
        Ok(format!("[{items}]"))
    }

    pub fn delete(&self, target: &str) -> Result<String, String> {
        if !Self::validate_target(target) {
            return Err("DENIED: backup file name is invalid".to_owned());
        }
        let path = self.directory.join(target);
        if !path.exists() {
            return Ok("{\"removed\":false}".to_owned());
        }
        fs::remove_file(&path).map_err(|_| "DENIED: backup file cannot be removed".to_owned())?;
        Ok("{\"removed\":true}".to_owned())
    }
}

fn valid_name(name: &str) -> bool {
    name.len() >= 2
        && name.len() <= MAX_NAME_LEN
        && name.as_bytes()[0].is_ascii_lowercase()
        && name.as_bytes()[name.len() - 1].is_ascii_alphanumeric()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
}

fn utc_stamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .map(|duration| duration.as_secs())
        .unwrap_or(0);
    let days = seconds / 86_400;
    let seconds_of_day = seconds % 86_400;
    let (year, month, day) = civil_from_days(days as i64);
    format!(
        "{year:04}{month:02}{day:02}T{:02}{:02}{:02}Z",
        seconds_of_day / 3_600,
        (seconds_of_day % 3_600) / 60,
        seconds_of_day % 60
    )
}

/// Howard Hinnant's days→civil conversion, so the helper needs no date crate.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = (z - era * 146_097) as u64;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe as i64 + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if month <= 2 { year + 1 } else { year }, month, day)
}

fn sha256_of(path: &Path) -> Result<String, String> {
    let bytes = fs::read(path).map_err(|_| "DENIED: backup file cannot be read".to_owned())?;
    let digest = Sha256::digest(&bytes);
    Ok(digest.iter().map(|byte| format!("{byte:02x}")).collect())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tar_binary() -> PathBuf {
        ["/usr/bin/tar", "/bin/tar"]
            .iter()
            .map(PathBuf::from)
            .find(|path| path.exists())
            .expect("tar binary")
    }

    fn setup() -> (tempfile::TempDir, BackupConfig, PathBuf) {
        let root = tempfile::tempdir().expect("temp dir");
        let backups = root.path().join("backups");
        fs::create_dir_all(&backups).expect("backup dir");
        let source = root.path().join("site");
        fs::create_dir_all(source.join("conf.d")).expect("source dir");
        fs::write(source.join("conf.d/app.conf"), "server { listen 80; }\n").expect("source file");
        (root, BackupConfig::at(&backups, &tar_binary()), source)
    }

    #[test]
    fn file_names_are_strictly_matched() {
        assert!(BackupConfig::validate_target(
            "site-20261030T091500Z.tar.gz"
        ));
        assert!(!BackupConfig::validate_target("site.tar.gz"));
        assert!(!BackupConfig::validate_target(
            "site-2026103T091500Z.tar.gz"
        ));
        assert!(!BackupConfig::validate_target(
            "site-20261030T09150Z.tar.gz"
        ));
        assert!(!BackupConfig::validate_target(
            "Site-20261030T091500Z.tar.gz"
        ));
        assert!(!BackupConfig::validate_target(
            "../site-20261030T091500Z.tar.gz"
        ));
        assert!(!BackupConfig::validate_target(
            "site-20261030t091500Z.tar.gz"
        ));
    }

    #[test]
    fn a_backup_is_created_listed_and_deleted() {
        let (_root, config, source) = setup();
        let payload = format!("{{\"source\":\"{}\",\"name\":\"site\"}}", source.display());
        let created = config.create(Some(&payload)).expect("create backup");
        assert!(created.contains("\"name\"") || created.contains("\"file\":\"site-"));

        let listed = config.list().expect("list backups");
        let file = serde_json::from_str::<serde_json::Value>(&created).expect("valid json")["file"]
            .as_str()
            .expect("file name")
            .to_owned();
        assert!(listed.contains(&file));
        assert!(listed.contains("\"sizeBytes\":"));
        assert!(file.ends_with(".tar.gz"));

        // The archive really contains the source tree.
        let archive = config.directory.join(&file);
        assert!(archive.exists());
        let contents = Command::new(tar_binary())
            .arg("-tzf")
            .arg(&archive)
            .output()
            .expect("list archive");
        let listing = String::from_utf8_lossy(&contents.stdout);
        assert!(listing.contains("site/conf.d/app.conf"));

        // The reported checksum is the file's checksum.
        assert!(created.contains(&sha256_of(&archive).expect("sha")));

        assert_eq!(config.delete(&file).expect("delete"), "{\"removed\":true}");
        assert_eq!(config.delete(&file).expect("delete"), "{\"removed\":false}");
        assert!(!archive.exists());
    }

    #[test]
    fn unsafe_sources_and_names_are_refused() {
        let (_root, config, source) = setup();
        let refused = |result: Result<String, String>| result.unwrap_err();

        assert!(refused(config.create(None)).contains("required"));
        assert!(refused(config.create(Some("not json"))).contains("schema"));

        let bad_name = format!(
            "{{\"source\":\"{}\",\"name\":\"Bad Name\"}}",
            source.display()
        );
        assert!(refused(config.create(Some(&bad_name))).contains("name"));

        let relative = "{\"source\":\"relative/path\",\"name\":\"site\"}";
        assert!(refused(config.create(Some(relative))).contains("absolute"));

        let missing = "{\"source\":\"/does/not/exist\",\"name\":\"site\"}";
        assert!(refused(config.create(Some(missing))).contains("does not exist"));

        let root = "{\"source\":\"/\",\"name\":\"site\"}";
        assert!(refused(config.create(Some(root))).contains("whole filesystem"));

        // Backing up the backup directory itself would recurse forever.
        let overlap = format!(
            "{{\"source\":\"{}\",\"name\":\"site\"}}",
            config.directory.display()
        );
        assert!(refused(config.create(Some(&overlap))).contains("overlaps"));
    }

    #[test]
    fn the_timestamp_is_a_valid_utc_stamp() {
        // 2026-10-30 is 20756 days after the epoch.
        assert_eq!(civil_from_days(20_756), (2026, 10, 30));
        assert_eq!(civil_from_days(0), (1970, 1, 1));
        let stamp = utc_stamp();
        assert_eq!(stamp.len(), 16);
        assert_eq!(&stamp[8..9], "T");
        assert_eq!(&stamp[15..], "Z");
        assert!(BackupConfig::validate_target(&format!(
            "site-{stamp}.tar.gz"
        )));
    }
}
