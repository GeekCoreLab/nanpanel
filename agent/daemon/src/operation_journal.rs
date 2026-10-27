use std::{
    collections::BTreeMap,
    os::unix::fs::MetadataExt,
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, bail};
use serde::{Deserialize, Serialize};
use uuid::Uuid;

const JOURNAL_FILE: &str = "operation-journal.json";
const MAX_JOURNAL_BYTES: u64 = 4 * 1024 * 1024;
const MAX_OPERATIONS: usize = 1024;
const MAX_CAPTURE_RESULT_BYTES: usize = 56 * 1024;

#[derive(Clone, Debug)]
pub enum Claim {
    New,
    InProgress,
    Replay { status: String, detail: String },
}

#[derive(Clone, Debug, Deserialize, Serialize)]
struct StoredOperation {
    operation_id: String,
    status: Option<String>,
    detail: Option<String>,
    updated_at_epoch_ms: u128,
}

#[derive(Debug, Deserialize, Serialize)]
struct JournalDocument {
    version: u8,
    operations: BTreeMap<String, StoredOperation>,
}

pub struct OperationJournal {
    directory: PathBuf,
    operations: BTreeMap<String, StoredOperation>,
}

impl OperationJournal {
    pub async fn open(directory: impl AsRef<Path>) -> Result<Self> {
        let directory = directory.as_ref().to_path_buf();
        if !directory.is_absolute() {
            bail!("Agent operation journal directory must be absolute");
        }
        tokio::fs::create_dir_all(&directory)
            .await
            .with_context(|| format!("create Agent state directory {}", directory.display()))?;
        let metadata = tokio::fs::symlink_metadata(&directory)
            .await
            .with_context(|| format!("inspect Agent state directory {}", directory.display()))?;
        let owner = nix::unistd::geteuid().as_raw();
        if !metadata.file_type().is_dir() || metadata.uid() != owner || metadata.mode() & 0o022 != 0
        {
            bail!("Agent state directory must be an owned directory and not group/world writable");
        }

        let path = directory.join(JOURNAL_FILE);
        let operations = match tokio::fs::symlink_metadata(&path).await {
            Ok(metadata) => {
                if !metadata.file_type().is_file()
                    || metadata.uid() != owner
                    || metadata.mode() & 0o077 != 0
                    || metadata.len() > MAX_JOURNAL_BYTES
                {
                    bail!(
                        "Agent operation journal must be a private, owned regular file under 4 MiB"
                    );
                }
                let bytes = tokio::fs::read(&path)
                    .await
                    .with_context(|| format!("read Agent operation journal {}", path.display()))?;
                let document: JournalDocument =
                    serde_json::from_slice(&bytes).context("decode Agent operation journal")?;
                if document.version != 1 {
                    bail!("unsupported Agent operation journal version");
                }
                if document.operations.len() > MAX_OPERATIONS {
                    bail!("Agent operation journal contains too many records");
                }
                for (key, operation) in &document.operations {
                    let result_valid = match operation.status.as_deref() {
                        None => operation.detail.is_none(),
                        Some("SUCCEEDED" | "FAILED") => operation.detail.is_some(),
                        Some(_) => false,
                    };
                    if key.is_empty()
                        || key.len() > 128
                        || operation.operation_id.is_empty()
                        || operation.operation_id.len() > 128
                        || !result_valid
                    {
                        bail!("Agent operation journal contains an invalid record");
                    }
                }
                document.operations
            }
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => BTreeMap::new(),
            Err(error) => return Err(error).context("inspect Agent operation journal"),
        };
        let journal = Self {
            directory,
            operations,
        };
        if !journal.directory.join(JOURNAL_FILE).exists() {
            journal.persist().await?;
        }
        Ok(journal)
    }

    pub async fn claim(&mut self, operation_id: &str, idempotency_key: &str) -> Result<Claim> {
        if operation_id.is_empty()
            || operation_id.len() > 128
            || idempotency_key.is_empty()
            || idempotency_key.len() > 128
        {
            bail!("Agent operation identity or idempotency key is invalid");
        }
        if let Some(existing) = self.operations.get(idempotency_key) {
            if existing.operation_id != operation_id {
                bail!("Agent idempotency key was reused for another operation");
            }
            return match (&existing.status, &existing.detail) {
                (Some(status), Some(detail)) => Ok(Claim::Replay {
                    status: status.clone(),
                    detail: detail.clone(),
                }),
                _ => Ok(Claim::InProgress),
            };
        }
        if self.operations.len() >= MAX_OPERATIONS {
            bail!(
                "Agent operation journal is full; refusing an operation until Core results are acknowledged"
            );
        }
        self.operations.insert(
            idempotency_key.to_owned(),
            StoredOperation {
                operation_id: operation_id.to_owned(),
                status: None,
                detail: None,
                updated_at_epoch_ms: now_epoch_ms()?,
            },
        );
        self.persist().await?;
        Ok(Claim::New)
    }

    pub async fn complete(
        &mut self,
        operation_id: &str,
        idempotency_key: &str,
        status: &str,
        detail: &str,
    ) -> Result<()> {
        self.complete_with_limit(operation_id, idempotency_key, status, detail, 2000)
            .await
    }

    pub async fn complete_capture(
        &mut self,
        operation_id: &str,
        idempotency_key: &str,
        status: &str,
        detail: &str,
    ) -> Result<()> {
        self.complete_with_limit(
            operation_id,
            idempotency_key,
            status,
            detail,
            MAX_CAPTURE_RESULT_BYTES,
        )
        .await
    }

    async fn complete_with_limit(
        &mut self,
        operation_id: &str,
        idempotency_key: &str,
        status: &str,
        detail: &str,
        maximum_detail_bytes: usize,
    ) -> Result<()> {
        if !matches!(status, "SUCCEEDED" | "FAILED") || detail.len() > maximum_detail_bytes {
            bail!("Agent operation result is invalid or too large");
        }
        let Some(operation) = self.operations.get_mut(idempotency_key) else {
            bail!("Agent operation completion has no prior durable claim");
        };
        if operation.operation_id != operation_id {
            bail!("Agent operation completion identity does not match its durable claim");
        }
        operation.status = Some(status.to_owned());
        operation.detail = Some(detail.to_owned());
        operation.updated_at_epoch_ms = now_epoch_ms()?;
        self.persist().await
    }

    pub async fn acknowledge(&mut self, operation_id: &str, idempotency_key: &str) -> Result<()> {
        let Some(operation) = self.operations.get(idempotency_key) else {
            return Ok(());
        };
        if operation.operation_id != operation_id {
            bail!("Agent result acknowledgement does not match its operation identity");
        }
        if operation.status.is_some() {
            self.operations.remove(idempotency_key);
            self.persist().await?;
        }
        Ok(())
    }

    pub fn recovery_results(&self) -> Vec<(String, String, String, String)> {
        self.operations
            .iter()
            .map(|(key, operation)| match (&operation.status, &operation.detail) {
                (Some(status), Some(detail)) => (
                    operation.operation_id.clone(),
                    key.clone(),
                    status.clone(),
                    detail.clone(),
                ),
                _ => (
                    operation.operation_id.clone(),
                    key.clone(),
                    "FAILED".to_owned(),
                    "UNCERTAIN: a previous Agent process claimed this operation without a durable result; refusing to execute it again".to_owned(),
                ),
            })
            .collect()
    }

    async fn persist(&self) -> Result<()> {
        let document = JournalDocument {
            version: 1,
            operations: self.operations.clone(),
        };
        let bytes = serde_json::to_vec(&document).context("encode Agent operation journal")?;
        if bytes.len() as u64 > MAX_JOURNAL_BYTES {
            bail!("Agent operation journal exceeds 4 MiB");
        }
        let path = self.directory.join(JOURNAL_FILE);
        let temporary = self
            .directory
            .join(format!(".operation-journal-{}.tmp", Uuid::new_v4()));
        let write_result = async {
            let mut file = tokio::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(&temporary)
                .await
                .with_context(|| format!("create journal staging file {}", temporary.display()))?;
            use tokio::io::AsyncWriteExt;
            file.write_all(&bytes)
                .await
                .context("write Agent operation journal")?;
            file.sync_all()
                .await
                .context("sync Agent operation journal")?;
            drop(file);
            tokio::fs::rename(&temporary, &path)
                .await
                .context("atomically replace Agent operation journal")?;
            let directory = tokio::fs::File::open(&self.directory)
                .await
                .context("open Agent state directory for sync")?;
            directory
                .sync_all()
                .await
                .context("sync Agent state directory")
        }
        .await;
        if write_result.is_err() {
            let _ = tokio::fs::remove_file(&temporary).await;
        }
        write_result
    }
}

fn now_epoch_ms() -> Result<u128> {
    Ok(SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .context("system clock is before Unix epoch")?
        .as_millis())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    fn temporary_directory() -> Result<PathBuf> {
        let path = std::env::temp_dir().join(format!("nanpanel-journal-{}", Uuid::new_v4()));
        std::fs::create_dir(&path)?;
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o700))?;
        Ok(path)
    }

    #[tokio::test]
    async fn completed_operations_replay_after_reopen_and_started_operations_are_not_reexecuted()
    -> Result<()> {
        let directory = temporary_directory()?;
        let mut journal = OperationJournal::open(&directory).await?;
        assert!(matches!(
            journal.claim("operation-1", "key-1").await?,
            Claim::New
        ));
        journal
            .complete("operation-1", "key-1", "SUCCEEDED", "active")
            .await?;
        assert!(matches!(
            journal.claim("operation-2", "key-2").await?,
            Claim::New
        ));

        let mut restored = OperationJournal::open(&directory).await?;
        assert!(matches!(
            restored.claim("operation-1", "key-1").await?,
            Claim::Replay { status, detail } if status == "SUCCEEDED" && detail == "active"
        ));
        assert!(matches!(
            restored.claim("operation-2", "key-2").await?,
            Claim::InProgress
        ));
        restored.acknowledge("operation-1", "key-1").await?;
        assert!(matches!(
            restored.claim("operation-1", "key-1").await?,
            Claim::New
        ));
        assert!(
            restored
                .claim("different-operation", "key-1")
                .await
                .is_err()
        );
        std::fs::remove_dir_all(directory)?;
        Ok(())
    }

    #[tokio::test]
    async fn capture_result_can_be_durably_replayed_above_the_normal_result_limit() -> Result<()> {
        let directory = temporary_directory()?;
        let detail = "x".repeat(4096);
        let mut journal = OperationJournal::open(&directory).await?;
        journal.claim("capture-1", "capture-key-1").await?;
        assert!(
            journal
                .complete("capture-1", "capture-key-1", "SUCCEEDED", &detail)
                .await
                .is_err()
        );
        journal
            .complete_capture("capture-1", "capture-key-1", "SUCCEEDED", &detail)
            .await?;

        let mut restored = OperationJournal::open(&directory).await?;
        assert!(matches!(
            restored.claim("capture-1", "capture-key-1").await?,
            Claim::Replay { status, detail: replayed }
                if status == "SUCCEEDED" && replayed == detail
        ));
        std::fs::remove_dir_all(directory)?;
        Ok(())
    }
}
