fn main() -> Result<(), Box<dyn std::error::Error>> {
    tonic_prost_build::configure()
        .boxed(".nanpanel.agent.v1.RelayedAgentMessage.message")
        .build_server(true)
        .build_client(false)
        .compile_protos(&["../protocol/agent.proto"], &["../protocol"])?;
    println!("cargo:rerun-if-changed=../protocol/agent.proto");
    Ok(())
}
