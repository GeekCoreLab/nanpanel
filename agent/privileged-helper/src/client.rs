use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{Context, Result};
use futures_util::{SinkExt, StreamExt};
use nanpanel_privileged_helper::{Request, Response};
use tokio::net::UnixStream;
use tokio_util::codec::{Framed, LinesCodec};
use uuid::Uuid;

#[tokio::main]
async fn main() -> Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    if args.len() != 3 {
        anyhow::bail!("usage: nanpanel-helper-client <socket> <capability> <target>");
    }
    let now = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as i64;
    let request = Request {
        operation_id: Uuid::new_v4().to_string(),
        idempotency_key: Uuid::new_v4().to_string(),
        capability: args[1].clone(),
        target: args[2].clone(),
        deadline_epoch_ms: now + 30_000,
    };
    let stream = UnixStream::connect(&args[0])
        .await
        .context("connect helper")?;
    let mut framed = Framed::new(stream, LinesCodec::new_with_max_length(16 * 1024));
    framed.send(serde_json::to_string(&request)?).await?;
    let response = framed
        .next()
        .await
        .context("helper closed without a response")??;
    let response: Response = serde_json::from_str(&response)?;
    println!("{}", serde_json::to_string(&response)?);
    if !response.ok {
        anyhow::bail!(
            "helper rejected operation: {}",
            response.error.unwrap_or_default()
        );
    }
    Ok(())
}
