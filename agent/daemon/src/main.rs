use nanpanel_agent::{
    client::{AgentConfig, run},
    gateway::serve_from_env,
};
use tracing_subscriber::EnvFilter;

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new("info")),
        )
        .init();
    if std::env::args().nth(1).as_deref() == Some("gateway") {
        serve_from_env().await
    } else {
        run(AgentConfig::from_env()?).await
    }
}
