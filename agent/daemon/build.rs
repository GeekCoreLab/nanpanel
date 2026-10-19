fn main() -> Result<(), Box<dyn std::error::Error>> {
    tonic_prost_build::configure()
        .build_server(true)
        .build_client(true)
        .compile_protos(&["../protocol/agent.proto"], &["../protocol"])?;
    println!("cargo:rerun-if-changed=../protocol/agent.proto");
    Ok(())
}
