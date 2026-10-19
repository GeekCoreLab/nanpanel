use std::{path::Path, time::Duration};

use anyhow::{Context, Result, anyhow, bail};
use futures_util::{SinkExt, StreamExt};
use serde::{Deserialize, Serialize};
use tokio::net::UnixStream;
use tokio_util::codec::{Framed, LinesCodec};
use uuid::Uuid;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct HelperRequest<'a> {
    operation_id: &'a str,
    idempotency_key: &'a str,
    capability: &'a str,
    target: &'a str,
    deadline_epoch_ms: i64,
}

#[derive(Deserialize)]
#[serde(deny_unknown_fields)]
struct HelperResponse {
    ok: bool,
    operation_id: String,
    result: Option<String>,
    error: Option<String>,
}

pub async fn status(socket_path: &Path) -> Result<bool> {
    let now = epoch_ms()?;
    match request(
        socket_path,
        &Uuid::new_v4().to_string(),
        &Uuid::new_v4().to_string(),
        "helper.status",
        "",
        now + 5_000,
    )
    .await
    {
        Ok(result) => Ok(result == "AVAILABLE"),
        Err(_) => Ok(false),
    }
}

pub async fn update(
    socket_path: &Path,
    operation_id: &str,
    idempotency_key: &str,
    capability: &str,
    target: &str,
    deadline_epoch_ms: i64,
) -> Result<String> {
    request(
        socket_path,
        operation_id,
        idempotency_key,
        capability,
        target,
        deadline_epoch_ms,
    )
    .await
}

async fn request(
    socket_path: &Path,
    operation_id: &str,
    idempotency_key: &str,
    capability: &str,
    target: &str,
    deadline_epoch_ms: i64,
) -> Result<String> {
    let now = epoch_ms()?;
    if deadline_epoch_ms <= now || deadline_epoch_ms > now + 10 * 60 * 1000 {
        bail!("Helper request deadline is expired or exceeds 10 minutes");
    }
    let stream = tokio::time::timeout(Duration::from_secs(5), UnixStream::connect(socket_path))
        .await
        .context("timed out connecting to Helper")??;
    let mut framed = Framed::new(stream, LinesCodec::new_with_max_length(16 * 1024));
    let request = HelperRequest {
        operation_id,
        idempotency_key,
        capability,
        target,
        deadline_epoch_ms,
    };
    framed.send(serde_json::to_string(&request)?).await?;
    let response = tokio::time::timeout(Duration::from_secs(125), framed.next())
        .await
        .context("timed out waiting for Helper")?
        .ok_or_else(|| anyhow!("Helper closed without a response"))??;
    let response: HelperResponse = serde_json::from_str(&response)?;
    if response.operation_id != operation_id {
        bail!("Helper response operation ID did not match request");
    }
    if !response.ok {
        bail!(
            "Helper rejected operation: {}",
            response.error.unwrap_or_default()
        );
    }
    response
        .result
        .ok_or_else(|| anyhow!("Helper response did not contain a result"))
}

fn epoch_ms() -> Result<i64> {
    let value = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .context("system clock is before Unix epoch")?
        .as_millis();
    i64::try_from(value).context("system clock value exceeds i64")
}
