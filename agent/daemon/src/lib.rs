pub mod v1 {
    tonic::include_proto!("nanpanel.agent.v1");
}

pub mod client;
pub mod direct_dns;
pub mod gateway;
pub mod helper;
pub mod operation_journal;
