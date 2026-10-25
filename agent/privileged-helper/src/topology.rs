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
    packet_taps: Vec<PacketTap>,
}

#[derive(Debug)]
struct PacketTap {
    node_id: Uuid,
    business_interfaces: [String; 2],
    monitor_interface: String,
}

pub(crate) fn available() -> bool {
    ["/usr/sbin/ip", "/usr/bin/ip"]
        .iter()
        .any(|candidate| trusted_binary(Path::new(candidate)))
        && ["/usr/sbin/tc", "/usr/bin/tc"]
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
        if !node_ids.insert(node.id) || !matches!(node.kind.as_str(), "SWITCH" | "PACKET_TAP") {
            return Err(
                "DENIED: Linux bridge Provider accepts unique SWITCH and PACKET_TAP nodes only"
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
    let mut packet_taps = Vec::new();
    for node in request
        .nodes
        .iter()
        .filter(|node| node.kind == "PACKET_TAP")
    {
        let input = request
            .links
            .iter()
            .find(|link| link.target_node_id == node.id && link.target_port == "in-1");
        let business = request
            .links
            .iter()
            .find(|link| link.source_node_id == node.id && link.source_port == "out-1");
        let monitor = request
            .links
            .iter()
            .find(|link| link.source_node_id == node.id && link.source_port == "out-2");
        let tap_link_count = request
            .links
            .iter()
            .filter(|link| link.source_node_id == node.id || link.target_node_id == node.id)
            .count();
        let (Some(input), Some(business), Some(monitor)) = (input, business, monitor) else {
            return Err(
                "DENIED: PACKET_TAP requires one input, one business output, and one monitor output"
                    .to_owned(),
            );
        };
        if tap_link_count != 3 {
            return Err("DENIED: PACKET_TAP supports exactly three connected ports".to_owned());
        }
        packet_taps.push(PacketTap {
            node_id: node.id,
            business_interfaces: [
                interface_for_node(input, &interfaces, node.id)?,
                interface_for_node(business, &interfaces, node.id)?,
            ],
            monitor_interface: interface_for_node(monitor, &interfaces, node.id)?,
        });
    }
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
        packet_taps,
    })
}

fn interface_for_node(
    link: &TopologyLink,
    interfaces: &HashMap<Uuid, (String, String)>,
    node_id: Uuid,
) -> Result<String, String> {
    let (source, target) = interfaces
        .get(&link.id)
        .ok_or_else(|| "DENIED: topology wire has no allocated interface".to_owned())?;
    if link.source_node_id == node_id {
        Ok(source.clone())
    } else if link.target_node_id == node_id {
        Ok(target.clone())
    } else {
        Err("DENIED: topology wire does not connect to the requested device".to_owned())
    }
}

impl CompiledTopology {
    fn is_monitor_interface(&self, interface: &str) -> bool {
        self.packet_taps
            .iter()
            .any(|tap| tap.monitor_interface == interface)
    }
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

fn tc_binary() -> Result<std::path::PathBuf, String> {
    trusted_executable(&["/usr/sbin/tc", "/usr/bin/tc"])
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
        for (interface, node_id, port, side) in [
            (
                source,
                link.source_node_id,
                link.source_port.as_str(),
                "source",
            ),
            (
                target,
                link.target_node_id,
                link.target_port.as_str(),
                "target",
            ),
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
            let mirror_output = topology
                .packet_taps
                .iter()
                .any(|tap| tap.node_id == node_id && port == "out-2");
            if !mirror_output {
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
            }
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

    if !topology.packet_taps.is_empty() {
        let tc = tc_binary()?;
        for tap in &topology.packet_taps {
            for interface in &tap.business_interfaces {
                in_namespace(
                    &tc,
                    &topology.namespace,
                    &[
                        "qdisc".to_owned(),
                        "add".to_owned(),
                        "dev".to_owned(),
                        interface.clone(),
                        "clsact".to_owned(),
                    ],
                )
                .await?;
                in_namespace(
                    &tc,
                    &topology.namespace,
                    &[
                        "filter".to_owned(),
                        "add".to_owned(),
                        "dev".to_owned(),
                        interface.clone(),
                        "egress".to_owned(),
                        "protocol".to_owned(),
                        "all".to_owned(),
                        "matchall".to_owned(),
                        "action".to_owned(),
                        "mirred".to_owned(),
                        "egress".to_owned(),
                        "mirror".to_owned(),
                        "dev".to_owned(),
                        tap.monitor_interface.clone(),
                    ],
                )
                .await?;
            }
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
                "FAILED: switch or packet tap {} bridge is missing or has drifted",
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
            let expected_master = if topology.is_monitor_interface(interface) {
                None
            } else {
                topology.bridges.get(&node_id).map(String::as_str)
            };
            let attached = links.iter().any(|actual| {
                actual.get("ifname").and_then(Value::as_str) == Some(interface)
                    && actual
                        .get("linkinfo")
                        .and_then(|value| value.get("info_kind"))
                        .and_then(Value::as_str)
                        == Some("veth")
                    && actual.get("alias").and_then(Value::as_str) == Some(expected_alias.as_str())
                    && actual.get("master").and_then(Value::as_str) == expected_master
            });
            if !attached {
                return Err(format!(
                    "FAILED: virtual wire {} is missing or has drifted",
                    link.id
                ));
            }
        }
    }
    if !topology.packet_taps.is_empty() {
        let tc = tc_binary()?;
        for tap in &topology.packet_taps {
            for interface in &tap.business_interfaces {
                let output = in_namespace(
                    &tc,
                    &topology.namespace,
                    &[
                        "-j".to_owned(),
                        "filter".to_owned(),
                        "show".to_owned(),
                        "dev".to_owned(),
                        interface.clone(),
                        "egress".to_owned(),
                    ],
                )
                .await?;
                if !has_packet_mirror(&output, &tap.monitor_interface) {
                    return Err(format!(
                        "FAILED: packet tap {} mirror filter is missing or has drifted",
                        tap.node_id
                    ));
                }
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
        "switches": topology.request.nodes.iter().filter(|node| node.kind == "SWITCH").count(),
        "packetTaps": topology.packet_taps.len(),
        "wires": topology.request.links.len(),
        "linkMetrics": link_metrics,
        "defaultRoutes": 0,
    })
    .to_string())
}

fn has_packet_mirror(output: &str, monitor_interface: &str) -> bool {
    let Ok(filters) = serde_json::from_str::<Vec<Value>>(output) else {
        return false;
    };
    filters.iter().any(|filter| {
        filter.get("kind").and_then(Value::as_str) == Some("matchall")
            && filter
                .pointer("/options/actions")
                .and_then(Value::as_array)
                .is_some_and(|actions| {
                    actions.iter().any(|action| {
                        action.get("kind").and_then(Value::as_str) == Some("mirred")
                            && action.get("mirred_action").and_then(Value::as_str) == Some("mirror")
                            && action.get("direction").and_then(Value::as_str) == Some("egress")
                            && action.get("to_dev").and_then(Value::as_str)
                                == Some(monitor_interface)
                    })
                })
    })
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
    use super::{
        compile, decode, has_packet_mirror, interface_statistics, validate_target_payload,
    };
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

    #[test]
    fn accepts_packet_tap_only_with_real_mirror_paths() -> anyhow::Result<()> {
        let topology = Uuid::new_v4();
        let upstream = Uuid::new_v4();
        let tap = Uuid::new_v4();
        let business = Uuid::new_v4();
        let monitor = Uuid::new_v4();
        let mut payload = json!({
            "action": "APPLY",
            "topologyId": topology,
            "revisionId": Uuid::new_v4(),
            "generation": 4,
            "nodes": [
                {"id": upstream, "kind": "SWITCH"},
                {"id": tap, "kind": "PACKET_TAP"},
                {"id": business, "kind": "SWITCH"},
                {"id": monitor, "kind": "SWITCH"}
            ],
            "links": [
                {"id": Uuid::new_v4(), "sourceNodeId": upstream, "sourcePort": "out-1",
                 "targetNodeId": tap, "targetPort": "in-1", "layer": "L2", "protocol": "ETHERNET"},
                {"id": Uuid::new_v4(), "sourceNodeId": tap, "sourcePort": "out-1",
                 "targetNodeId": business, "targetPort": "in-1", "layer": "L2", "protocol": "ETHERNET"},
                {"id": Uuid::new_v4(), "sourceNodeId": tap, "sourcePort": "out-2",
                 "targetNodeId": monitor, "targetPort": "in-1", "layer": "L2", "protocol": "ETHERNET"}
            ]
        });
        let compiled = compile(
            &topology.to_string(),
            decode(&payload.to_string()).map_err(anyhow::Error::msg)?,
        )
        .map_err(anyhow::Error::msg)?;
        assert_eq!(compiled.packet_taps.len(), 1);
        let compiled_tap = compiled
            .packet_taps
            .first()
            .ok_or_else(|| anyhow::anyhow!("compiled packet tap is missing"))?;
        assert_eq!(compiled_tap.business_interfaces.len(), 2);
        assert_ne!(
            compiled_tap.business_interfaces[0],
            compiled_tap.monitor_interface
        );

        let links = payload["links"]
            .as_array()
            .ok_or_else(|| anyhow::anyhow!("packet tap links are missing"))?;
        let first = links
            .first()
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("packet tap input link is missing"))?;
        let second = links
            .get(1)
            .cloned()
            .ok_or_else(|| anyhow::anyhow!("packet tap business link is missing"))?;
        payload["links"] = json!([first, second]);
        assert!(
            validate_target_payload(&topology.to_string(), Some(&payload.to_string())).is_err()
        );
        Ok(())
    }

    #[test]
    fn verifies_the_egress_mirror_action_and_monitor_device() {
        let filter = r#"[{"kind":"matchall","options":{"actions":[{"kind":"mirred","mirred_action":"mirror","direction":"egress","to_dev":"npl01a"}]}}]"#;
        assert!(has_packet_mirror(filter, "npl01a"));
        assert!(!has_packet_mirror(filter, "npl02a"));
        assert!(!has_packet_mirror("not-json", "npl01a"));
    }
}
