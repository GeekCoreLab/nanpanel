use std::{
    collections::{HashMap, HashSet},
    path::Path,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

use crate::{Request, run_fixed_command, trusted_executable};

const NETWORK_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_NODES: usize = 16;
const MAX_LINKS: usize = 64;

#[derive(Clone, Copy, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "SCREAMING_SNAKE_CASE")]
enum Action {
    Apply,
    Verify,
    Compensate,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct TopologyRequest {
    action: Action,
    topology_id: Uuid,
    revision_id: Uuid,
    generation: u64,
    nodes: Vec<TopologyNode>,
    links: Vec<TopologyLink>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct TopologyNode {
    id: Uuid,
    kind: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct TopologyLink {
    id: Uuid,
    source_node_id: Uuid,
    source_port: String,
    target_node_id: Uuid,
    target_port: String,
    layer: String,
    protocol: String,
}

#[derive(Debug)]
struct CompiledTopology {
    request: TopologyRequest,
    namespace: String,
    bridges: HashMap<Uuid, String>,
    interfaces: HashMap<Uuid, (String, String)>,
}

pub(crate) fn available() -> bool {
    ["/usr/sbin/ip", "/usr/bin/ip"]
        .iter()
        .any(|candidate| trusted_binary(Path::new(candidate)))
}

pub(crate) fn validate_target_payload(target: &str, payload: Option<&str>) -> Result<(), String> {
    let payload = payload.ok_or_else(|| "DENIED: topology payload is required".to_owned())?;
    let request = decode(payload)?;
    compile(target, request).map(|_| ())
}

pub(crate) async fn execute(request: &Request) -> Result<String, String> {
    let payload = request
        .payload_json
        .as_deref()
        .ok_or_else(|| "DENIED: topology payload is required".to_owned())?;
    let topology = compile(&request.target, decode(payload)?)?;
    match topology.request.action {
        Action::Apply => apply(&topology).await,
        Action::Verify => verify(&topology).await,
        Action::Compensate => compensate(&topology).await,
    }
}

fn decode(payload: &str) -> Result<TopologyRequest, String> {
    serde_json::from_str(payload).map_err(|_| "DENIED: topology payload is invalid".to_owned())
}

fn compile(target: &str, request: TopologyRequest) -> Result<CompiledTopology, String> {
    if target != request.topology_id.to_string()
        || request.generation == 0
        || request.nodes.is_empty()
        || request.nodes.len() > MAX_NODES
        || request.links.len() > MAX_LINKS
    {
        return Err("DENIED: topology identity or size is invalid".to_owned());
    }

    let mut node_ids = HashSet::new();
    for node in &request.nodes {
        if !node_ids.insert(node.id) || node.kind != "SWITCH" {
            return Err(
                "DENIED: Linux bridge Provider currently accepts unique SWITCH nodes only"
                    .to_owned(),
            );
        }
    }

    let mut link_ids = HashSet::new();
    let mut used_ports = HashSet::new();
    for link in &request.links {
        if !link_ids.insert(link.id)
            || link.source_node_id == link.target_node_id
            || !node_ids.contains(&link.source_node_id)
            || !node_ids.contains(&link.target_node_id)
            || !valid_port(&link.source_port, "out-")
            || !valid_port(&link.target_port, "in-")
            || link.layer != "L2"
            || link.protocol != "ETHERNET"
            || !used_ports.insert((link.source_node_id, link.source_port.as_str()))
            || !used_ports.insert((link.target_node_id, link.target_port.as_str()))
        {
            return Err(
                "DENIED: Linux bridge links must be unique L2 Ethernet switch ports".to_owned(),
            );
        }
    }

    let mut nodes = request.nodes.iter().collect::<Vec<_>>();
    nodes.sort_by_key(|node| node.id);
    let bridges = nodes
        .iter()
        .enumerate()
        .map(|(index, node)| (node.id, format!("npb{index:02x}")))
        .collect::<HashMap<_, _>>();
    let mut links = request.links.iter().collect::<Vec<_>>();
    links.sort_by_key(|link| link.id);
    let interfaces = links
        .iter()
        .enumerate()
        .map(|(index, link)| {
            (
                link.id,
                (format!("npl{index:02x}a"), format!("npl{index:02x}b")),
            )
        })
        .collect::<HashMap<_, _>>();
    let namespace = format!(
        "nanpanel-{}-{}-{}",
        request.topology_id,
        request.generation,
        request.revision_id.simple().to_string()[..8].to_owned()
    );

    Ok(CompiledTopology {
        request,
        namespace,
        bridges,
        interfaces,
    })
}

fn valid_port(value: &str, prefix: &str) -> bool {
    value.strip_prefix(prefix).is_some_and(|number| {
        number
            .parse::<u8>()
            .is_ok_and(|parsed| (1..=16).contains(&parsed) && parsed.to_string() == number)
    })
}

fn trusted_binary(path: &Path) -> bool {
    path.to_str()
        .is_some_and(|value| trusted_executable(&[value]).is_ok())
}

fn ip_binary() -> Result<std::path::PathBuf, String> {
    trusted_executable(&["/usr/sbin/ip", "/usr/bin/ip"])
}

async fn namespace_exists(ip: &Path, namespace: &str) -> Result<bool, String> {
    let output = run_fixed_command(
        ip,
        &["netns".to_owned(), "list".to_owned()],
        NETWORK_TIMEOUT,
    )
    .await?;
    Ok(output
        .lines()
        .any(|line| line.split_whitespace().next() == Some(namespace)))
}

async fn in_namespace(program: &Path, namespace: &str, args: &[String]) -> Result<String, String> {
    let mut command = vec!["netns".to_owned(), "exec".to_owned(), namespace.to_owned()];
    command.push(program.to_string_lossy().into_owned());
    command.extend_from_slice(args);
    run_fixed_command(&ip_binary()?, &command, NETWORK_TIMEOUT).await
}

async fn apply(topology: &CompiledTopology) -> Result<String, String> {
    let ip = ip_binary()?;
    if namespace_exists(&ip, &topology.namespace).await? {
        return match verify(topology).await {
            Ok(result) => Ok(result),
            Err(_) => Err("FAILED: this topology revision already has a namespace with drift; verify or compensate it before retrying".to_owned()),
        };
    }

    run_fixed_command(
        &ip,
        &[
            "netns".to_owned(),
            "add".to_owned(),
            topology.namespace.clone(),
        ],
        NETWORK_TIMEOUT,
    )
    .await?;
    let result = apply_created(topology, &ip).await;
    if result.is_err() {
        let _ = run_fixed_command(
            &ip,
            &[
                "netns".to_owned(),
                "del".to_owned(),
                topology.namespace.clone(),
            ],
            NETWORK_TIMEOUT,
        )
        .await;
    }
    result
}

async fn apply_created(topology: &CompiledTopology, ip: &Path) -> Result<String, String> {
    in_namespace(
        ip,
        &topology.namespace,
        &[
            "link".to_owned(),
            "set".to_owned(),
            "lo".to_owned(),
            "up".to_owned(),
        ],
    )
    .await?;
    for node in &topology.request.nodes {
        let bridge = &topology.bridges[&node.id];
        in_namespace(
            ip,
            &topology.namespace,
            &[
                "link".to_owned(),
                "add".to_owned(),
                bridge.clone(),
                "type".to_owned(),
                "bridge".to_owned(),
            ],
        )
        .await?;
        in_namespace(
            ip,
            &topology.namespace,
            &[
                "link".to_owned(),
                "set".to_owned(),
                "dev".to_owned(),
                bridge.clone(),
                "alias".to_owned(),
                format!(
                    "nanpanel:{}:{}:{}:{}",
                    topology.request.topology_id,
                    topology.request.revision_id,
                    topology.request.generation,
                    node.id
                ),
            ],
        )
        .await?;
        in_namespace(
            ip,
            &topology.namespace,
            &[
                "link".to_owned(),
                "set".to_owned(),
                "dev".to_owned(),
                bridge.clone(),
                "up".to_owned(),
            ],
        )
        .await?;
    }

    for link in &topology.request.links {
        let (source, target) = &topology.interfaces[&link.id];
        in_namespace(
            ip,
            &topology.namespace,
            &[
                "link".to_owned(),
                "add".to_owned(),
                source.clone(),
                "type".to_owned(),
                "veth".to_owned(),
                "peer".to_owned(),
                "name".to_owned(),
                target.clone(),
            ],
        )
        .await?;
        for (interface, node_id, side) in [
            (source, link.source_node_id, "source"),
            (target, link.target_node_id, "target"),
        ] {
            in_namespace(
                ip,
                &topology.namespace,
                &[
                    "link".to_owned(),
                    "set".to_owned(),
                    "dev".to_owned(),
                    interface.clone(),
                    "alias".to_owned(),
                    format!(
                        "nanpanel:{}:{}:{}:{}:{}:{}",
                        topology.request.topology_id,
                        topology.request.revision_id,
                        topology.request.generation,
                        link.id,
                        node_id,
                        side
                    ),
                ],
            )
            .await?;
            in_namespace(
                ip,
                &topology.namespace,
                &[
                    "link".to_owned(),
                    "set".to_owned(),
                    "dev".to_owned(),
                    interface.clone(),
                    "master".to_owned(),
                    topology.bridges[&node_id].clone(),
                ],
            )
            .await?;
            in_namespace(
                ip,
                &topology.namespace,
                &[
                    "link".to_owned(),
                    "set".to_owned(),
                    "dev".to_owned(),
                    interface.clone(),
                    "up".to_owned(),
                ],
            )
            .await?;
        }
    }

    verify(topology).await
}

async fn verify(topology: &CompiledTopology) -> Result<String, String> {
    let ip = ip_binary()?;
    if !namespace_exists(&ip, &topology.namespace).await? {
        return Err("FAILED: topology network namespace does not exist".to_owned());
    }
    let route_output = in_namespace(
        &ip,
        &topology.namespace,
        &[
            "-j".to_owned(),
            "route".to_owned(),
            "show".to_owned(),
            "default".to_owned(),
        ],
    )
    .await?;
    let routes = serde_json::from_str::<Value>(&route_output)
        .map_err(|_| "UNCERTAIN: network namespace route output is invalid".to_owned())?;
    if !routes.as_array().is_some_and(Vec::is_empty) {
        return Err("FAILED: isolated topology namespace contains a default route".to_owned());
    }

    let link_output = in_namespace(
        &ip,
        &topology.namespace,
        &[
            "-j".to_owned(),
            "-s".to_owned(),
            "-d".to_owned(),
            "link".to_owned(),
            "show".to_owned(),
        ],
    )
    .await?;
    let links = serde_json::from_str::<Vec<Value>>(&link_output)
        .map_err(|_| "UNCERTAIN: network namespace link output is invalid".to_owned())?;
    for node in &topology.request.nodes {
        let expected = format!(
            "nanpanel:{}:{}:{}:{}",
            topology.request.topology_id,
            topology.request.revision_id,
            topology.request.generation,
            node.id
        );
        let bridge = links.iter().find(|link| {
            link.get("ifname").and_then(Value::as_str)
                == topology.bridges.get(&node.id).map(String::as_str)
                && link
                    .get("linkinfo")
                    .and_then(|value| value.get("info_kind"))
                    .and_then(Value::as_str)
                    == Some("bridge")
                && link.get("alias").and_then(Value::as_str) == Some(expected.as_str())
        });
        if bridge.is_none() {
            return Err(format!(
                "FAILED: switch {} bridge is missing or has drifted",
                node.id
            ));
        }
    }
    for link in &topology.request.links {
        let (source, target) = &topology.interfaces[&link.id];
        for (interface, node_id, side) in [
            (source, link.source_node_id, "source"),
            (target, link.target_node_id, "target"),
        ] {
            let expected_alias = format!(
                "nanpanel:{}:{}:{}:{}:{}:{}",
                topology.request.topology_id,
                topology.request.revision_id,
                topology.request.generation,
                link.id,
                node_id,
                side
            );
            let attached = links.iter().any(|actual| {
                actual.get("ifname").and_then(Value::as_str) == Some(interface)
                    && actual
                        .get("linkinfo")
                        .and_then(|value| value.get("info_kind"))
                        .and_then(Value::as_str)
                        == Some("veth")
                    && actual.get("alias").and_then(Value::as_str) == Some(expected_alias.as_str())
                    && actual.get("master").and_then(Value::as_str)
                        == topology.bridges.get(&node_id).map(String::as_str)
            });
            if !attached {
                return Err(format!(
                    "FAILED: virtual wire {} is missing or has drifted",
                    link.id
                ));
            }
        }
    }
    let mut link_metrics = Vec::with_capacity(topology.request.links.len());
    for link in &topology.request.links {
        let (source, target) = &topology.interfaces[&link.id];
        let source_stats = interface_statistics(&links, source).ok_or_else(|| {
            format!(
                "UNCERTAIN: virtual wire {} source counters are unavailable",
                link.id
            )
        })?;
        let target_stats = interface_statistics(&links, target).ok_or_else(|| {
            format!(
                "UNCERTAIN: virtual wire {} target counters are unavailable",
                link.id
            )
        })?;
        link_metrics.push(serde_json::json!({
            "linkId": link.id,
            "source": source_stats,
            "target": target_stats,
        }));
    }
    let observed_at = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "UNCERTAIN: system clock is before the Unix epoch".to_owned())?
        .as_millis();
    Ok(serde_json::json!({
        "verified": true,
        "observedAt": observed_at,
        "topologyId": topology.request.topology_id,
        "revisionId": topology.request.revision_id,
        "generation": topology.request.generation,
        "namespace": topology.namespace,
        "switches": topology.request.nodes.len(),
        "wires": topology.request.links.len(),
        "linkMetrics": link_metrics,
        "defaultRoutes": 0,
    })
    .to_string())
}

fn interface_statistics(links: &[Value], interface: &str) -> Option<Value> {
    let device = links
        .iter()
        .find(|link| link.get("ifname").and_then(Value::as_str) == Some(interface))?;
    let statistics = device.get("stats64").or_else(|| device.get("stats"))?;
    let rx = statistics.get("rx")?;
    let tx = statistics.get("tx")?;
    Some(serde_json::json!({
        "rxBytes": rx.get("bytes").and_then(Value::as_u64)?,
        "rxPackets": rx.get("packets").and_then(Value::as_u64)?,
        "txBytes": tx.get("bytes").and_then(Value::as_u64)?,
        "txPackets": tx.get("packets").and_then(Value::as_u64)?,
    }))
}

async fn compensate(topology: &CompiledTopology) -> Result<String, String> {
    let ip = ip_binary()?;
    if namespace_exists(&ip, &topology.namespace).await? {
        if !namespace_owned(topology, &ip).await? {
            return Err(
                "DENIED: refusing to remove a namespace without this topology's ownership marker"
                    .to_owned(),
            );
        }
        run_fixed_command(
            &ip,
            &[
                "netns".to_owned(),
                "del".to_owned(),
                topology.namespace.clone(),
            ],
            NETWORK_TIMEOUT,
        )
        .await?;
    }
    Ok(serde_json::json!({
        "compensated": true,
        "topologyId": topology.request.topology_id,
        "revisionId": topology.request.revision_id,
        "generation": topology.request.generation,
    })
    .to_string())
}

async fn namespace_owned(topology: &CompiledTopology, ip: &Path) -> Result<bool, String> {
    let Some(node) = topology.request.nodes.first() else {
        return Ok(false);
    };
    let bridge = &topology.bridges[&node.id];
    let expected = format!(
        "nanpanel:{}:{}:{}:{}",
        topology.request.topology_id,
        topology.request.revision_id,
        topology.request.generation,
        node.id
    );
    let output = in_namespace(
        ip,
        &topology.namespace,
        &[
            "-j".to_owned(),
            "-d".to_owned(),
            "link".to_owned(),
            "show".to_owned(),
            "dev".to_owned(),
            bridge.clone(),
        ],
    )
    .await?;
    let links = serde_json::from_str::<Vec<Value>>(&output)
        .map_err(|_| "UNCERTAIN: topology ownership marker output is invalid".to_owned())?;
    Ok(links.iter().any(|link| {
        link.get("ifname").and_then(Value::as_str) == Some(bridge.as_str())
            && link
                .get("linkinfo")
                .and_then(|value| value.get("info_kind"))
                .and_then(Value::as_str)
                == Some("bridge")
            && link.get("alias").and_then(Value::as_str) == Some(expected.as_str())
    }))
}

#[cfg(test)]
mod tests {
    use super::{compile, decode, interface_statistics, validate_target_payload};
    use serde_json::json;
    use uuid::Uuid;

    fn sample() -> (String, String) {
        let topology = Uuid::new_v4();
        let revision = Uuid::new_v4();
        let payload = json!({
            "action": "APPLY",
            "topologyId": topology,
            "revisionId": revision,
            "generation": 3,
            "nodes": [
                {"id": Uuid::new_v4(), "kind": "SWITCH"},
                {"id": Uuid::new_v4(), "kind": "SWITCH"}
            ],
            "links": []
        });
        (topology.to_string(), payload.to_string())
    }

    #[test]
    fn accepts_only_isolated_linux_bridge_topologies() -> anyhow::Result<()> {
        let (target, payload) = sample();
        validate_target_payload(&target, Some(&payload)).map_err(anyhow::Error::msg)?;
        Ok(())
    }

    #[test]
    fn rejects_target_mismatch_unknown_fields_and_non_bridge_devices() {
        let (target, payload) = sample();
        assert!(validate_target_payload(&Uuid::new_v4().to_string(), Some(&payload)).is_err());
        let unknown = payload.replace(
            "\"generation\":3",
            "\"generation\":3,\"command\":\"ip route add\"",
        );
        assert!(validate_target_payload(&target, Some(&unknown)).is_err());
        let unsupported = payload.replace("\"SWITCH\"", "\"ROUTER\"");
        assert!(validate_target_payload(&target, Some(&unsupported)).is_err());
    }

    #[test]
    fn compiles_stable_interface_names_and_enforces_link_protocols() -> anyhow::Result<()> {
        let (target, payload) = sample();
        let mut value = serde_json::from_str::<serde_json::Value>(&payload)?;
        let left = value["nodes"][0]["id"].clone();
        let right = value["nodes"][1]["id"].clone();
        value["links"] = json!([{
            "id": Uuid::new_v4(),
            "sourceNodeId": left,
            "sourcePort": "out-1",
            "targetNodeId": right,
            "targetPort": "in-1",
            "layer": "L2",
            "protocol": "ETHERNET"
        }]);
        let payload = value.to_string();
        let compiled = compile(&target, decode(&payload).map_err(anyhow::Error::msg)?)
            .map_err(anyhow::Error::msg)?;
        assert_eq!(compiled.bridges.len(), 2);
        assert_eq!(compiled.interfaces.len(), 1);
        assert_eq!(compiled.namespace.matches('-').count(), 7);
        value["links"][0]["protocol"] = json!("IP");
        assert!(validate_target_payload(&target, Some(&value.to_string())).is_err());
        value["links"][0]["protocol"] = json!("ETHERNET");
        value["links"][0]["sourcePort"] = json!("out-01");
        assert!(validate_target_payload(&target, Some(&value.to_string())).is_err());
        Ok(())
    }

    #[test]
    fn reads_bounded_kernel_interface_counters() {
        let links = vec![
            json!({
                "ifname": "npl00a",
                "stats64": {
                    "rx": {"bytes": 1200, "packets": 12},
                    "tx": {"bytes": 3400, "packets": 34}
                }
            }),
            json!({"ifname": "npl00b", "stats": {"rx": {}, "tx": {}}}),
        ];
        assert_eq!(
            interface_statistics(&links, "npl00a"),
            Some(json!({
                "rxBytes": 1200,
                "rxPackets": 12,
                "txBytes": 3400,
                "txPackets": 34
            }))
        );
        assert_eq!(interface_statistics(&links, "npl00b"), None);
        assert_eq!(interface_statistics(&links, "missing"), None);
    }
}
