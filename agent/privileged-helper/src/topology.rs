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
    firewall_policy: Option<FirewallPolicy>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct FirewallPolicy {
    default_action: FirewallAction,
    rules: Vec<FirewallRule>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct FirewallRule {
    input_port: String,
    output_port: String,
    protocol: FirewallProtocol,
    destination_port: u16,
    action: FirewallAction,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum FirewallAction {
    Accept,
    Drop,
}

impl FirewallAction {
    fn nft_verdict(self) -> &'static str {
        match self {
            Self::Accept => "accept",
            Self::Drop => "drop",
        }
    }

    fn nft_policy(self) -> &'static str {
        self.nft_verdict()
    }
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum FirewallProtocol {
    Tcp,
    Udp,
    Sctp,
}

impl FirewallProtocol {
    fn nft_name(self) -> &'static str {
        match self {
            Self::Tcp => "tcp",
            Self::Udp => "udp",
            Self::Sctp => "sctp",
        }
    }
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
    firewalls: Vec<Firewall>,
}

#[derive(Debug)]
struct PacketTap {
    node_id: Uuid,
    business_interfaces: [String; 2],
    monitor_interface: String,
}

#[derive(Debug)]
struct Firewall {
    node_id: Uuid,
    table: String,
    policy: FirewallPolicy,
    input_interfaces: HashMap<String, String>,
    output_interfaces: HashMap<String, String>,
}

pub(crate) fn available() -> bool {
    ["/usr/sbin/ip", "/usr/bin/ip"]
        .iter()
        .any(|candidate| trusted_binary(Path::new(candidate)))
        && ["/usr/sbin/tc", "/usr/bin/tc"]
            .iter()
            .any(|candidate| trusted_binary(Path::new(candidate)))
        && ["/usr/sbin/nft", "/usr/bin/nft"]
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
    let mut node_kinds = HashMap::new();
    for node in &request.nodes {
        if !node_ids.insert(node.id)
            || !matches!(node.kind.as_str(), "SWITCH" | "PACKET_TAP" | "FIREWALL")
        {
            return Err("DENIED: Linux netns Provider accepts unique SWITCH, PACKET_TAP, and FIREWALL nodes only".to_owned());
        }
        if (node.kind == "FIREWALL") != node.firewall_policy.is_some() {
            return Err("DENIED: FIREWALL requires a typed policy, and other device kinds cannot include one".to_owned());
        }
        node_kinds.insert(node.id, node.kind.as_str());
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
            || !supported_link(link, &node_kinds)
            || !used_ports.insert((link.source_node_id, link.source_port.as_str()))
            || !used_ports.insert((link.target_node_id, link.target_port.as_str()))
        {
            return Err(
                "DENIED: Linux netns wires must be unique supported L2 Ethernet device ports"
                    .to_owned(),
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
    let firewalls = compile_firewalls(&request, &interfaces)?;
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
        firewalls,
    })
}

fn supported_link(link: &TopologyLink, node_kinds: &HashMap<Uuid, &str>) -> bool {
    let Some(source_kind) = node_kinds.get(&link.source_node_id) else {
        return false;
    };
    let Some(target_kind) = node_kinds.get(&link.target_node_id) else {
        return false;
    };
    matches!(*source_kind, "SWITCH" | "PACKET_TAP" | "FIREWALL")
        && matches!(*target_kind, "SWITCH" | "PACKET_TAP" | "FIREWALL")
        && link.layer == "L2"
        && link.protocol == "ETHERNET"
}

fn compile_firewalls(
    request: &TopologyRequest,
    interfaces: &HashMap<Uuid, (String, String)>,
) -> Result<Vec<Firewall>, String> {
    let mut nodes = request
        .nodes
        .iter()
        .filter(|node| node.kind == "FIREWALL")
        .collect::<Vec<_>>();
    nodes.sort_by_key(|node| node.id);
    nodes
        .into_iter()
        .enumerate()
        .map(|(index, node)| {
            let policy = node
                .firewall_policy
                .as_ref()
                .ok_or_else(|| "DENIED: FIREWALL requires a policy".to_owned())?;
            if policy.rules.len() > 64 {
                return Err("DENIED: FIREWALL policy has too many rules".to_owned());
            }
            let mut input_interfaces = HashMap::new();
            let mut output_interfaces = HashMap::new();
            for link in request.links.iter().filter(|link| {
                link.source_node_id == node.id || link.target_node_id == node.id
            }) {
                if link.target_node_id == node.id {
                    input_interfaces.insert(
                        link.target_port.clone(),
                        interface_for_node(link, interfaces, node.id)?,
                    );
                }
                if link.source_node_id == node.id {
                    output_interfaces.insert(
                        link.source_port.clone(),
                        interface_for_node(link, interfaces, node.id)?,
                    );
                }
            }
            if input_interfaces.is_empty() || output_interfaces.is_empty() {
                return Err("DENIED: FIREWALL requires at least one connected input and output".to_owned());
            }
            let mut matches = HashSet::new();
            for rule in &policy.rules {
                if rule.destination_port == 0
                    || !input_interfaces.contains_key(&rule.input_port)
                    || !output_interfaces.contains_key(&rule.output_port)
                {
                    return Err("DENIED: FIREWALL rule must reference connected ports and a valid destination port".to_owned());
                }
                let key = format!(
                    "{}:{}:{}:{}",
                    rule.input_port,
                    rule.output_port,
                    rule.protocol.nft_name(),
                    rule.destination_port
                );
                if !matches.insert(key) {
                    return Err("DENIED: FIREWALL policy contains duplicate match conditions".to_owned());
                }
            }
            Ok(Firewall {
                node_id: node.id,
                table: format!("npf{index:02x}"),
                policy: policy.clone(),
                input_interfaces,
                output_interfaces,
            })
        })
        .collect()
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

fn nft_binary() -> Result<std::path::PathBuf, String> {
    trusted_executable(&["/usr/sbin/nft", "/usr/bin/nft"])
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
    if !topology.firewalls.is_empty() {
        let nft = nft_binary()?;
        for firewall in &topology.firewalls {
            in_namespace(
                &nft,
                &topology.namespace,
                &[
                    "add".to_owned(),
                    "table".to_owned(),
                    "bridge".to_owned(),
                    firewall.table.clone(),
                ],
            )
            .await?;
            let chain = format!(
                "{{ type filter hook forward priority 0; policy {}; }}",
                firewall.policy.default_action.nft_policy()
            );
            in_namespace(
                &nft,
                &topology.namespace,
                &[
                    "add".to_owned(),
                    "chain".to_owned(),
                    "bridge".to_owned(),
                    firewall.table.clone(),
                    "forward".to_owned(),
                    chain,
                ],
            )
            .await?;
            for (index, rule) in firewall.policy.rules.iter().enumerate() {
                let input = firewall
                    .input_interfaces
                    .get(&rule.input_port)
                    .ok_or_else(|| "DENIED: firewall input interface is missing".to_owned())?;
                let output = firewall
                    .output_interfaces
                    .get(&rule.output_port)
                    .ok_or_else(|| "DENIED: firewall output interface is missing".to_owned())?;
                let comment = firewall_rule_comment(topology, firewall, index);
                in_namespace(
                    &nft,
                    &topology.namespace,
                    &[
                        "add".to_owned(),
                        "rule".to_owned(),
                        "bridge".to_owned(),
                        firewall.table.clone(),
                        "forward".to_owned(),
                        "iifname".to_owned(),
                        input.clone(),
                        "oifname".to_owned(),
                        output.clone(),
                        rule.protocol.nft_name().to_owned(),
                        "dport".to_owned(),
                        rule.destination_port.to_string(),
                        rule.action.nft_verdict().to_owned(),
                        "comment".to_owned(),
                        comment,
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
    if !topology.firewalls.is_empty() {
        let nft = nft_binary()?;
        for firewall in &topology.firewalls {
            let output = in_namespace(
                &nft,
                &topology.namespace,
                &[
                    "-j".to_owned(),
                    "list".to_owned(),
                    "chain".to_owned(),
                    "bridge".to_owned(),
                    firewall.table.clone(),
                    "forward".to_owned(),
                ],
            )
            .await?;
            if !firewall_rules_match(&output, topology, firewall) {
                return Err(format!(
                    "FAILED: firewall {} rules or default action are missing or have drifted",
                    firewall.node_id
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
        "switches": topology.request.nodes.iter().filter(|node| node.kind == "SWITCH").count(),
        "packetTaps": topology.packet_taps.len(),
        "firewalls": topology.firewalls.len(),
        "wires": topology.request.links.len(),
        "linkMetrics": link_metrics,
        "defaultRoutes": 0,
    })
    .to_string())
}

fn firewall_rule_comment(
    topology: &CompiledTopology,
    firewall: &Firewall,
    rule_index: usize,
) -> String {
    format!(
        "nanpanel:{}:{}:{}:{}:rule:{}",
        topology.request.topology_id,
        topology.request.revision_id,
        topology.request.generation,
        firewall.node_id,
        rule_index
    )
}

fn firewall_rules_match(output: &str, topology: &CompiledTopology, firewall: &Firewall) -> bool {
    let Ok(document) = serde_json::from_str::<Value>(output) else {
        return false;
    };
    let Some(entries) = document.pointer("/nftables").and_then(Value::as_array) else {
        return false;
    };
    let chain_exists = entries.iter().any(|entry| {
        entry.get("chain").is_some_and(|chain| {
            chain.get("family").and_then(Value::as_str) == Some("bridge")
                && chain.get("table").and_then(Value::as_str) == Some(firewall.table.as_str())
                && chain.get("name").and_then(Value::as_str) == Some("forward")
                && chain.get("hook").and_then(Value::as_str) == Some("forward")
                && chain.get("policy").and_then(Value::as_str)
                    == Some(firewall.policy.default_action.nft_policy())
        })
    });
    if !chain_exists {
        return false;
    }
    let rules = entries
        .iter()
        .filter_map(|entry| entry.get("rule"))
        .filter(|rule| {
            rule.get("family").and_then(Value::as_str) == Some("bridge")
                && rule.get("table").and_then(Value::as_str) == Some(firewall.table.as_str())
                && rule.get("chain").and_then(Value::as_str) == Some("forward")
        })
        .collect::<Vec<_>>();
    if rules.len() != firewall.policy.rules.len() {
        return false;
    }
    firewall
        .policy
        .rules
        .iter()
        .enumerate()
        .all(|(index, expected)| {
            let Some(input) = firewall.input_interfaces.get(&expected.input_port) else {
                return false;
            };
            let Some(output) = firewall.output_interfaces.get(&expected.output_port) else {
                return false;
            };
            let comment = firewall_rule_comment(topology, firewall, index);
            rules.get(index).is_some_and(|rule| {
                rule.get("comment").and_then(Value::as_str) == Some(comment.as_str())
                    && firewall_rule_expression_matches(
                        rule,
                        input,
                        output,
                        expected.protocol,
                        expected.destination_port,
                        expected.action,
                    )
            })
        })
}

fn firewall_rule_expression_matches(
    rule: &Value,
    input: &str,
    output: &str,
    protocol: FirewallProtocol,
    destination_port: u16,
    action: FirewallAction,
) -> bool {
    let Some(expressions) = rule.pointer("/expr").and_then(Value::as_array) else {
        return false;
    };
    if expressions.len() != 4 {
        return false;
    }
    let matches = expressions
        .iter()
        .filter_map(|expression| expression.get("match"));
    let mut found_input = false;
    let mut found_output = false;
    let mut found_port = false;
    for matcher in matches {
        let left = matcher.get("left");
        let right = matcher.get("right");
        if left
            .and_then(|value| value.pointer("/meta/key"))
            .and_then(Value::as_str)
            == Some("iifname")
            && right.and_then(Value::as_str) == Some(input)
        {
            found_input = true;
        }
        if left
            .and_then(|value| value.pointer("/meta/key"))
            .and_then(Value::as_str)
            == Some("oifname")
            && right.and_then(Value::as_str) == Some(output)
        {
            found_output = true;
        }
        if left
            .and_then(|value| value.pointer("/payload/protocol"))
            .and_then(Value::as_str)
            == Some(protocol.nft_name())
            && left
                .and_then(|value| value.pointer("/payload/field"))
                .and_then(Value::as_str)
                == Some("dport")
            && right.and_then(Value::as_u64) == Some(u64::from(destination_port))
        {
            found_port = true;
        }
    }
    let verdict = match action {
        FirewallAction::Accept => "accept",
        FirewallAction::Drop => "drop",
    };
    found_input
        && found_output
        && found_port
        && expressions
            .iter()
            .any(|expression| expression.get(verdict).is_some_and(Value::is_null))
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
        compile, decode, firewall_rules_match, has_packet_mirror, interface_statistics,
        validate_target_payload,
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

    #[test]
    fn compiles_and_verifies_a_bounded_bridge_firewall_policy() -> anyhow::Result<()> {
        let topology_id = Uuid::new_v4();
        let source = Uuid::new_v4();
        let firewall = Uuid::new_v4();
        let target = Uuid::new_v4();
        let revision = Uuid::new_v4();
        let mut payload = json!({
            "action": "APPLY",
            "topologyId": topology_id,
            "revisionId": revision,
            "generation": 5,
            "nodes": [
                {"id": source, "kind": "SWITCH"},
                {"id": firewall, "kind": "FIREWALL", "firewallPolicy": {
                    "defaultAction": "DROP",
                    "rules": [{"inputPort": "in-1", "outputPort": "out-1",
                        "protocol": "TCP", "destinationPort": 8080, "action": "ACCEPT"}]
                }},
                {"id": target, "kind": "SWITCH"}
            ],
            "links": [
                {"id": Uuid::new_v4(), "sourceNodeId": source, "sourcePort": "out-1",
                 "targetNodeId": firewall, "targetPort": "in-1", "layer": "L2", "protocol": "ETHERNET"},
                {"id": Uuid::new_v4(), "sourceNodeId": firewall, "sourcePort": "out-1",
                 "targetNodeId": target, "targetPort": "in-1", "layer": "L2", "protocol": "ETHERNET"}
            ]
        });
        let compiled = compile(
            &topology_id.to_string(),
            decode(&payload.to_string()).map_err(anyhow::Error::msg)?,
        )
        .map_err(anyhow::Error::msg)?;
        assert_eq!(compiled.firewalls.len(), 1);
        let compiled_firewall = compiled
            .firewalls
            .first()
            .ok_or_else(|| anyhow::anyhow!("compiled firewall is missing"))?;
        assert_eq!(compiled_firewall.table, "npf00");
        let input_interface = compiled_firewall
            .input_interfaces
            .get("in-1")
            .ok_or_else(|| anyhow::anyhow!("firewall input interface is missing"))?;
        let output_interface = compiled_firewall
            .output_interfaces
            .get("out-1")
            .ok_or_else(|| anyhow::anyhow!("firewall output interface is missing"))?;
        let rule = format!(
            "nanpanel:{}:{}:5:{}:rule:0",
            topology_id, revision, firewall
        );
        let document = json!({"nftables": [
            {"chain": {"family": "bridge", "table": "npf00", "name": "forward",
                "hook": "forward", "policy": "drop"}},
            {"rule": {"family": "bridge", "table": "npf00", "chain": "forward",
                "comment": rule,
                "expr": [
                    {"match": {"left": {"meta": {"key": "iifname"}}, "right": input_interface}},
                    {"match": {"left": {"meta": {"key": "oifname"}}, "right": output_interface}},
                    {"match": {"left": {"payload": {"protocol": "tcp", "field": "dport"}}, "right": 8080}},
                    {"accept": null}
                ]}}
        ]});
        assert!(firewall_rules_match(
            &document.to_string(),
            &compiled,
            compiled_firewall
        ));
        let mut drifted = document.clone();
        drifted["nftables"][0]["chain"]["policy"] = json!("accept");
        assert!(!firewall_rules_match(
            &drifted.to_string(),
            &compiled,
            compiled_firewall
        ));
        payload["nodes"][1]["firewallPolicy"]["rules"][0]["inputPort"] = json!("in-2");
        assert!(
            validate_target_payload(&topology_id.to_string(), Some(&payload.to_string())).is_err()
        );
        Ok(())
    }
}
