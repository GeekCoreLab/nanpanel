//! Managed cron jobs: one file per job under a root-owned directory (normally
//! `/etc/cron.d`), named `nanpanel-<id>` and carrying an ownership marker. Only files we
//! wrote are ever read, rewritten or removed, so a hand-written crontab entry is never
//! touched by the panel.

use std::{
    fs::{self, OpenOptions},
    io::Write,
    os::unix::fs::OpenOptionsExt,
    path::{Path, PathBuf},
};

use anyhow::{Context, bail};
use serde::Deserialize;

const MARKER: &str = "# Managed by NaNPanel; job ";
const MAX_SCHEDULE_BYTES: usize = 128;
const MAX_COMMAND_BYTES: usize = 900;
const MAX_JOBS: usize = 200;

#[derive(Clone, Debug)]
pub struct CronConfig {
    jobs_dir: PathBuf,
}

#[derive(Debug, Deserialize)]
struct JobPayload {
    schedule: String,
    command: String,
    #[serde(default)]
    user: Option<String>,
}

#[derive(Debug, PartialEq, Eq)]
struct Job {
    id: String,
    schedule: String,
    user: String,
    command: String,
}

impl CronConfig {
    pub fn from_environment() -> anyhow::Result<Option<Self>> {
        let Some(directory) = std::env::var_os("NANPANEL_CRON_DIR") else {
            return Ok(None);
        };
        let directory = PathBuf::from(directory);
        if !directory.is_absolute() {
            bail!("NANPANEL_CRON_DIR must be an absolute path");
        }
        let jobs_dir = fs::canonicalize(&directory)
            .with_context(|| format!("resolve cron directory {}", directory.display()))?;
        let metadata = fs::metadata(&jobs_dir).context("inspect cron directory")?;
        if !metadata.is_dir() {
            bail!("cron directory must be a directory");
        }
        Ok(Some(Self { jobs_dir }))
    }

    pub fn validate_target(target: &str) -> bool {
        target.starts_with("nanpanel-")
            && target.len() <= 42
            && target.as_bytes().get(9).is_some_and(u8::is_ascii_lowercase)
            && target
                .bytes()
                .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'-')
            && !target.ends_with('-')
    }

    pub fn write_job(&self, target: &str, payload_json: Option<&str>) -> Result<String, String> {
        if !Self::validate_target(target) {
            return Err("DENIED: managed cron job name is invalid".to_owned());
        }
        let Some(payload_json) = payload_json else {
            return Err("DENIED: cron job payload is required".to_owned());
        };
        let payload: JobPayload = serde_json::from_str(payload_json)
            .map_err(|_| "DENIED: cron job payload schema is invalid".to_owned())?;
        let job = validate(target, payload)?;

        fs::create_dir_all(&self.jobs_dir)
            .map_err(|_| "DENIED: cron directory is not writable".to_owned())?;
        let path = self.jobs_dir.join(&job.id);
        let temporary = self.jobs_dir.join(format!(".{}.tmp", job.id));
        {
            let mut file = OpenOptions::new()
                .write(true)
                .create(true)
                .truncate(true)
                .mode(0o644)
                .open(&temporary)
                .map_err(|_| "DENIED: cron job file cannot be written".to_owned())?;
            file.write_all(render(&job).as_bytes())
                .map_err(|_| "DENIED: cron job file cannot be written".to_owned())?;
            file.sync_all()
                .map_err(|_| "DENIED: cron job file cannot be flushed".to_owned())?;
        }
        fs::rename(&temporary, &path)
            .map_err(|_| "DENIED: cron job file cannot be installed".to_owned())?;

        // Read back what is now on disk: a rewrite that does not parse is not a success.
        let stored = read_job(&path)
            .map_err(|_| "DENIED: written cron job could not be read back".to_owned())?;
        if stored != job {
            return Err("DENIED: written cron job does not round-trip".to_owned());
        }
        Ok(format!(
            "{{\"job\":\"{}\",\"schedule\":\"{}\",\"user\":\"{}\"}}",
            job.id, job.schedule, job.user
        ))
    }

    pub fn list_jobs(&self) -> Result<String, String> {
        let entries = fs::read_dir(&self.jobs_dir)
            .map_err(|_| "DENIED: cron directory cannot be read".to_owned())?;
        let mut jobs = Vec::new();
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().into_owned();
            if !Self::validate_target(&name) {
                continue;
            }
            let Ok(job) = read_job(&entry.path()) else {
                continue;
            };
            jobs.push(format!(
                "{{\"id\":\"{}\",\"schedule\":\"{}\",\"user\":\"{}\",\"command\":{}}}",
                job.id,
                job.schedule,
                job.user,
                serde_json::json!(job.command)
            ));
            if jobs.len() >= MAX_JOBS {
                break;
            }
        }
        jobs.sort();
        Ok(format!("[{}]", jobs.join(",")))
    }

    pub fn remove_job(&self, target: &str) -> Result<String, String> {
        if !Self::validate_target(target) {
            return Err("DENIED: managed cron job name is invalid".to_owned());
        }
        let path = self.jobs_dir.join(target);
        if !path.exists() {
            // Idempotent: a job that is already gone is not an error.
            return Ok("{\"removed\":false}".to_owned());
        }
        let content = fs::read_to_string(&path)
            .map_err(|_| "DENIED: cron job file cannot be read".to_owned())?;
        if !content.starts_with(MARKER) {
            return Err("DENIED: cron job file is not owned by NaNPanel".to_owned());
        }
        fs::remove_file(&path).map_err(|_| "DENIED: cron job file cannot be removed".to_owned())?;
        Ok("{\"removed\":true}".to_owned())
    }
}

fn validate(id: &str, payload: JobPayload) -> Result<Job, String> {
    let schedule = payload.schedule.trim().to_owned();
    if !valid_schedule(&schedule) {
        return Err("DENIED: cron schedule must be five fields of [0-9*/,-]".to_owned());
    }
    let command = payload.command.trim().to_owned();
    if command.is_empty()
        || command.len() > MAX_COMMAND_BYTES
        || command
            .chars()
            .any(|value| value == '\n' || value == '\r' || value == '\0')
    {
        return Err("DENIED: cron command must be a single line of at most 900 bytes".to_owned());
    }
    // Running cron entries as anything but root would need per-user files and privileges we
    // do not model yet, so the panel only ever writes root jobs.
    let user = payload.user.unwrap_or_else(|| "root".to_owned());
    if user != "root" {
        return Err("DENIED: managed cron jobs run as root only".to_owned());
    }
    Ok(Job {
        id: id.to_owned(),
        schedule,
        user,
        command,
    })
}

fn valid_schedule(schedule: &str) -> bool {
    if schedule.is_empty() || schedule.len() > MAX_SCHEDULE_BYTES || schedule.contains('@') {
        return false;
    }
    let fields: Vec<&str> = schedule.split_whitespace().collect();
    fields.len() == 5
        && fields.iter().all(|field| {
            !field.is_empty()
                && field.len() <= 32
                && field
                    .bytes()
                    .all(|byte| byte.is_ascii_digit() || matches!(byte, b'*' | b'/' | b',' | b'-'))
        })
}

fn render(job: &Job) -> String {
    format!(
        "{MARKER}{}\nSHELL=/bin/sh\nPATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n{} {} {}\n",
        job.id, job.schedule, job.user, job.command
    )
}

fn read_job(path: &Path) -> anyhow::Result<Job> {
    let content = fs::read_to_string(path).context("read cron job file")?;
    if !content.starts_with(MARKER) {
        bail!("cron job file is not owned by NaNPanel");
    }
    let mut job: Option<Job> = None;
    for line in content.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') || line.contains('=') {
            continue;
        }
        let parts: Vec<&str> = line.splitn(7, ' ').collect();
        if parts.len() < 7 {
            bail!("cron job line is malformed");
        }
        job = Some(Job {
            id: String::new(),
            schedule: parts[0..5].join(" "),
            user: parts[5].to_owned(),
            command: parts[6..].join(" ").trim().to_owned(),
        });
    }
    let mut job = job.context("cron job file contains no job line")?;
    job.id = path
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_default();
    Ok(job)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn config(directory: &Path) -> CronConfig {
        CronConfig {
            jobs_dir: directory.to_path_buf(),
        }
    }

    fn payload(schedule: &str, command: &str) -> String {
        format!("{{\"schedule\":\"{schedule}\",\"command\":\"{command}\"}}")
    }

    #[test]
    fn only_prefixed_names_are_accepted() {
        assert!(CronConfig::validate_target("nanpanel-backup"));
        assert!(CronConfig::validate_target("nanpanel-a1"));
        assert!(!CronConfig::validate_target("backup"));
        assert!(!CronConfig::validate_target("nanpanel-"));
        assert!(!CronConfig::validate_target("nanpanel-Backup"));
        assert!(!CronConfig::validate_target("nanpanel-backup-"));
        assert!(!CronConfig::validate_target(
            "nanpanel-backup/../../etc/passwd"
        ));
        assert!(!CronConfig::validate_target("nanpanel-1abc"));
    }

    #[test]
    fn schedules_must_be_five_numeric_fields() {
        assert!(valid_schedule("*/5 * * * *"));
        assert!(valid_schedule("0 3 1,15 * 0-6"));
        assert!(!valid_schedule("* * * *"));
        assert!(!valid_schedule("* * * * * *"));
        assert!(!valid_schedule("@daily"));
        assert!(!valid_schedule("* * * * *; rm -rf /"));
        assert!(!valid_schedule(""));
    }

    #[test]
    fn commands_must_be_a_single_line() {
        let directory = tempdir();
        let config = config(&directory);
        let error = config
            .write_job(
                "nanpanel-x",
                Some(&payload("* * * * *", "echo hi\\nrm -rf /")),
            )
            .expect_err("multi-line command must be refused");
        assert!(error.contains("single line"));
        let error = config
            .write_job("nanpanel-x", Some("{\"schedule\":\"* * * * *\"}"))
            .expect_err("missing command must be refused");
        assert!(error.contains("schema"));
        let error = config
            .write_job(
                "nanpanel-x",
                Some(&payload("* * * * *", "echo hi").replace("}", ",\"user\":\"nobody\"}")),
            )
            .expect_err("non-root user must be refused");
        assert!(error.contains("root only"));
    }

    #[test]
    fn a_job_round_trips_and_lists() {
        let directory = tempdir();
        let config = config(&directory);
        let written = config
            .write_job(
                "nanpanel-backup",
                Some(&payload("*/15 * * * *", "/usr/local/bin/backup.sh")),
            )
            .expect("job should be written");
        assert!(written.contains("nanpanel-backup"));
        let listed = config.list_jobs().expect("list should succeed");
        assert!(listed.contains("\"id\":\"nanpanel-backup\""));
        assert!(listed.contains("\"schedule\":\"*/15 * * * *\""));
        assert!(listed.contains("\"user\":\"root\""));
        assert!(listed.contains("/usr/local/bin/backup.sh"));
        let file = fs::read_to_string(directory.join("nanpanel-backup")).expect("read job");
        assert!(file.starts_with(MARKER));
        assert!(file.contains("SHELL=/bin/sh"));
    }

    #[test]
    fn foreign_files_are_neither_listed_nor_removed() {
        let directory = tempdir();
        let config = config(&directory);
        fs::write(
            directory.join("system-backup"),
            "0 1 * * * root /bin/true\n",
        )
        .expect("write foreign file");
        fs::create_dir_all(&directory).expect("directory");
        let listed = config.list_jobs().expect("list should succeed");
        assert_eq!(listed, "[]");
        assert_eq!(
            config.remove_job("system-backup").unwrap_err(),
            "DENIED: managed cron job name is invalid"
        );
        assert!(directory.join("system-backup").exists());
    }

    #[test]
    fn a_file_without_the_marker_is_refused_on_remove() {
        let directory = tempdir();
        let config = config(&directory);
        fs::write(
            directory.join("nanpanel-planted"),
            "0 1 * * * root /bin/true\n",
        )
        .expect("write planted file");
        assert!(config.remove_job("nanpanel-planted").is_err());
        assert!(directory.join("nanpanel-planted").exists());
    }

    #[test]
    fn removal_is_idempotent() {
        let directory = tempdir();
        let config = config(&directory);
        config
            .write_job(
                "nanpanel-once",
                Some(&payload("0 4 * * *", "/bin/echo done")),
            )
            .expect("write job");
        assert_eq!(
            config.remove_job("nanpanel-once").unwrap(),
            "{\"removed\":true}"
        );
        assert_eq!(
            config.remove_job("nanpanel-once").unwrap(),
            "{\"removed\":false}"
        );
    }

    fn tempdir() -> PathBuf {
        let path = std::env::temp_dir().join(format!("nanpanel-cron-{}", uuid::Uuid::new_v4()));
        fs::create_dir_all(&path).expect("create temp dir");
        path
    }
}
