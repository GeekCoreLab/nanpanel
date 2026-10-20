fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("cargo:rerun-if-env-changed=NANPANEL_BUILD_VERSION");
    let package_version = std::env::var("CARGO_PKG_VERSION")?;
    let build_version = std::env::var("NANPANEL_BUILD_VERSION")
        .ok()
        .filter(|value| !value.trim().is_empty());
    let agent_version = build_version
        .map(|build| format!("{package_version}+{build}"))
        .unwrap_or(package_version);
    println!("cargo:rustc-env=NANPANEL_AGENT_VERSION={agent_version}");

    tonic_prost_build::configure()
        .boxed(".nanpanel.agent.v1.RelayedAgentMessage.message")
        .build_server(true)
        .build_client(true)
        .compile_protos(&["../protocol/agent.proto"], &["../protocol"])?;
    println!("cargo:rerun-if-changed=../protocol/agent.proto");
    Ok(())
}
