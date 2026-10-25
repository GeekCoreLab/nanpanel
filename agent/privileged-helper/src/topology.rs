use std::{
    collections::{HashMap, HashSet},
    fs::{self, OpenOptions},
    io::Write,
    net::Ipv4Addr,
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use serde::Deserialize;
use serde_json::Value;
use uuid::Uuid;

use crate::{Request, run_fixed_command, trusted_executable};

const NETWORK_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_NODES: usize = 16;
const MAX_LINKS: usize = 64;

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
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
    switch_backend: Option<SwitchBackend>,
    firewall_policy: Option<FirewallPolicy>,
    router_configuration: Option<RouterConfiguration>,
}

#[derive(Clone, Copy, Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
enum SwitchBackend {
    Bridge,
    Openvswitch,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RouterConfiguration {
    interfaces: Vec<RouterInterfaceAddress>,
    routes: Vec<RouterStaticRoute>,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RouterInterfaceAddress {
    port: String,
    address: String,
}

#[derive(Clone, Debug, Deserialize)]
#[serde(deny_unknown_fields, rename_all = "camelCase")]
struct RouterStaticRoute {
    destination: String,
    gateway: Option<String>,
    output_port: String,
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
    ovs_switches: HashSet<Uuid>,
    interfaces: HashMap<Uuid, (String, String)>,
    routers: Vec<Router>,
    packet_taps: Vec<PacketTap>,
    firewalls: Vec<Firewall>,
}

impl CompiledTopology {
    fn uses_ovs(&self) -> bool {
        !self.ovs_switches.is_empty()
    }

    fn is_ovs_switch(&self, node_id: Uuid) -> bool {
        self.ovs_switches.contains(&node_id)
    }
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

#[derive(Debug)]
struct Router {
    node_id: Uuid,
    interfaces: Vec<RouterAddress>,
    routes: Vec<RouterRoute>,
}

#[derive(Debug)]
struct RouterAddress {
    interface: String,
    address: String,
}

#[derive(Debug)]
struct RouterRoute {
    destination: String,
    gateway: Option<String>,
    interface: String,
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
        && ["/usr/sbin/sysctl", "/usr/bin/sysctl"]
            .iter()
            .any(|candidate| trusted_binary(Path::new(candidate)))
}

pub(crate) fn ovs_available() -> bool {
    use std::os::unix::fs::FileTypeExt;
    ["/usr/sbin/ovsdb-server", "/usr/bin/ovsdb-server"]
        .iter()
        .any(|candidate| trusted_binary(Path::new(candidate)))
        && ["/usr/sbin/ovs-vswitchd", "/usr/bin/ovs-vswitchd"]
            .iter()
            .any(|candidate| trusted_binary(Path::new(candidate)))
        && ["/usr/sbin/ovsdb-tool", "/usr/bin/ovsdb-tool"]
            .iter()
            .any(|candidate| trusted_binary(Path::new(candidate)))
        && ["/usr/sbin/ovs-vsctl", "/usr/bin/ovs-vsctl"]
            .iter()
            .any(|candidate| trusted_binary(Path::new(candidate)))
        && ["/usr/sbin/ovs-appctl", "/usr/bin/ovs-appctl"]
            .iter()
            .any(|candidate| trusted_binary(Path::new(candidate)))
        && ovs_schema().is_some_and(|path| trusted_system_file(&path))
        && fs::symlink_metadata("/dev/net/tun")
            .is_ok_and(|metadata| metadata.file_type().is_char_device())
        && OpenOptions::new()
            .read(true)
            .write(true)
            .open("/dev/net/tun")
            .is_ok()
}

fn ovs_schema() -> Option<PathBuf> {
    [
        "/usr/share/openvswitch/vswitch.ovsschema",
        "/usr/local/share/openvswitch/vswitch.ovsschema",
    ]
    .iter()
    .map(PathBuf::from)
    .find(|path| trusted_system_file(path))
}

fn trusted_system_file(path: &Path) -> bool {
    use std::os::unix::fs::MetadataExt;
    fs::metadata(path).is_ok_and(|metadata| {
        metadata.is_file() && metadata.uid() == 0 && metadata.mode() & 0o022 == 0
    })
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
    let mut ovs_switches = HashSet::new();
    for node in &request.nodes {
        if !node_ids.insert(node.id)
            || !matches!(
                node.kind.as_str(),
                "SWITCH" | "PACKET_TAP" | "FIREWALL" | "ROUTER"
            )
        {
            return Err("DENIED: Linux netns Provider accepts unique SWITCH, PACKET_TAP, FIREWALL, and ROUTER nodes only".to_owned());
        }
        match (&node.kind[..], node.switch_backend) {
            ("SWITCH", Some(SwitchBackend::Openvswitch)) => {
                ovs_switches.insert(node.id);
            }
            ("SWITCH", Some(SwitchBackend::Bridge) | None) => {}
            (_, None) => {}
            _ => return Err("DENIED: only SWITCH nodes can select a switch backend".to_owned()),
        }
        if (node.kind == "FIREWALL") != node.firewall_policy.is_some()
            || (node.kind == "ROUTER") != node.router_configuration.is_some()
        {
            return Err(
                "DENIED: FIREWALL and ROUTER require their matching typed configuration".to_owned(),
            );
        }
        node_kinds.insert(node.id, node.kind.as_str());
    }
    if !ovs_switches.is_empty() {
        if request
            .nodes
            .iter()
            .any(|node| matches!(node.kind.as_str(), "FIREWALL" | "PACKET_TAP"))
        {
            return Err(
                "DENIED: OVS topology plans cannot include Firewall or Packet Tap devices"
                    .to_owned(),
            );
        }
        if request.action == Action::Apply && !ovs_available() {
            return Err(
                "DENIED: OVS backend requires trusted OVS userspace tools, schema, and /dev/net/tun"
                    .to_owned(),
            );
        }
    }
    if request
        .nodes
        .iter()
        .filter(|node| node.kind == "ROUTER")
        .count()
        > 1
    {
        return Err(
            "DENIED: one isolated topology namespace supports at most one Router routing table"
                .to_owned(),
        );
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
    let routers = compile_routers(&request, &interfaces)?;
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
        ovs_switches,
        interfaces,
        routers,
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
    matches!(
        *source_kind,
        "SWITCH" | "PACKET_TAP" | "FIREWALL" | "ROUTER"
    ) && matches!(
        *target_kind,
        "SWITCH" | "PACKET_TAP" | "FIREWALL" | "ROUTER"
    ) && link.layer == "L2"
        && link.protocol == "ETHERNET"
}

fn compile_routers(
    request: &TopologyRequest,
    interfaces: &HashMap<Uuid, (String, String)>,
) -> Result<Vec<Router>, String> {
    let mut nodes = request
        .nodes
        .iter()
        .filter(|node| node.kind == "ROUTER")
        .collect::<Vec<_>>();
    nodes.sort_by_key(|node| node.id);
    nodes
        .into_iter()
        .map(|node| {
            let configuration = node
                .router_configuration
                .as_ref()
                .ok_or_else(|| "DENIED: ROUTER requires IPv4 configuration".to_owned())?;
            if configuration.interfaces.is_empty()
                || configuration.interfaces.len() > 16
                || configuration.routes.len() > 64
            {
                return Err("DENIED: ROUTER configuration exceeds its interface or route limits".to_owned());
            }
            let mut connected_ports = HashSet::new();
            let mut output_ports = HashSet::new();
            let mut port_interfaces = HashMap::new();
            for link in request.links.iter().filter(|link| {
                link.source_node_id == node.id || link.target_node_id == node.id
            }) {
                let port = if link.source_node_id == node.id {
                    output_ports.insert(link.source_port.clone());
                    &link.source_port
                } else {
                    &link.target_port
                };
                connected_ports.insert(port.clone());
                port_interfaces.insert(
                    port.clone(),
                    interface_for_node(link, interfaces, node.id)?,
                );
            }
            let mut configured = HashMap::new();
            let mut addresses = Vec::new();
            for address in &configuration.interfaces {
                let parsed = parse_ipv4_cidr(&address.address)
                    .ok_or_else(|| "DENIED: ROUTER interface requires a valid IPv4 CIDR".to_owned())?;
                if !connected_ports.contains(&address.port)
                    || configured.insert(address.port.clone(), parsed).is_some()
                {
                    return Err("DENIED: ROUTER interfaces must reference unique connected ports".to_owned());
                }
                let interface = port_interfaces
                    .get(&address.port)
                    .ok_or_else(|| "DENIED: ROUTER interface is not connected".to_owned())?;
                addresses.push(RouterAddress {
                    interface: interface.clone(),
                    address: address.address.clone(),
                });
            }
            if !configured.keys().cloned().collect::<HashSet<_>>().is_superset(&connected_ports) {
                return Err("DENIED: ROUTER requires an IPv4 address on every connected interface".to_owned());
            }
            let mut destinations = HashSet::new();
            let mut routes = Vec::new();
            for route in &configuration.routes {
                let destination = parse_ipv4_cidr(&route.destination)
                    .filter(|network| network.canonical)
                    .ok_or_else(|| "DENIED: ROUTER route destination must be a canonical IPv4 network".to_owned())?;
                let address = configured.get(&route.output_port)
                    .ok_or_else(|| "DENIED: ROUTER route output must reference an addressed interface".to_owned())?;
                if !port_interfaces.contains_key(&route.output_port)
                    || !output_ports.contains(&route.output_port)
                    || !destinations.insert(route.destination.as_str())
                    || configured.values().any(|connected| connected.network == destination.network
                        && connected.prefix == destination.prefix)
                {
                    return Err("DENIED: ROUTER routes must be unique and cannot replace directly connected networks".to_owned());
                }
                if let Some(gateway) = &route.gateway {
                    let gateway = parse_ipv4(gateway)
                        .ok_or_else(|| "DENIED: ROUTER next hop must be a valid IPv4 address".to_owned())?;
                    if (gateway & prefix_mask(address.prefix)) != address.network {
                        return Err("DENIED: ROUTER next hop must belong to the output interface subnet".to_owned());
                    }
                }
                routes.push(RouterRoute {
                    destination: route.destination.clone(),
                    gateway: route.gateway.clone(),
                    interface: port_interfaces[&route.output_port].clone(),
                });
            }
            Ok(Router {
                node_id: node.id,
                interfaces: addresses,
                routes,
            })
        })
        .collect()
}

#[derive(Clone, Copy, Debug)]
struct Ipv4Cidr {
    network: u32,
    prefix: u8,
    canonical: bool,
}

fn parse_ipv4_cidr(value: &str) -> Option<Ipv4Cidr> {
    let (address, prefix) = value.split_once('/')?;
    let address = parse_ipv4(address)?;
    let prefix = prefix.parse::<u8>().ok().filter(|prefix| *prefix <= 32)?;
    let network = address & prefix_mask(prefix);
    Some(Ipv4Cidr {
        network,
        prefix,
        canonical: address == network,
    })
}

fn parse_ipv4(value: &str) -> Option<u32> {
    let address = value.parse::<Ipv4Addr>().ok()?;
    let [a, b, c, d] = address.octets();
    Some(u32::from_be_bytes([a, b, c, d]))
}

fn prefix_mask(prefix: u8) -> u32 {
    if prefix == 0 {
        0
    } else {
        u32::MAX << (32 - prefix)
    }
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

    fn is_router(&self, node_id: Uuid) -> bool {
        self.request
            .nodes
            .iter()
            .any(|node| node.id == node_id && node.kind == "ROUTER")
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

fn sysctl_binary() -> Result<std::path::PathBuf, String> {
    trusted_executable(&["/usr/sbin/sysctl", "/usr/bin/sysctl"])
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

fn ovs_run_dir(topology: &CompiledTopology) -> PathBuf {
    Path::new("/run/nanpanel/topology-ovs").join(&topology.namespace)
}

fn ovs_owner(topology: &CompiledTopology) -> String {
    format!(
        "nanpanel:{}:{}:{}",
        topology.request.topology_id, topology.request.revision_id, topology.request.generation
    )
}

fn ovs_owned_dir(path: &Path, mode: u32) -> Result<bool, String> {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    let created = match fs::create_dir(path) {
        Ok(()) => {
            fs::set_permissions(path, fs::Permissions::from_mode(mode))
                .map_err(|_| "FAILED: cannot secure OVS runtime directory".to_owned())?;
            true
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => false,
        Err(_) => return Err("FAILED: cannot create OVS runtime directory".to_owned()),
    };
    let metadata = fs::symlink_metadata(path)
        .map_err(|_| "FAILED: cannot inspect OVS runtime directory".to_owned())?;
    if !metadata.file_type().is_dir() || metadata.uid() != 0 || metadata.mode() & 0o022 != 0 {
        return Err(
            "DENIED: OVS runtime directory is not a protected root-owned directory".to_owned(),
        );
    }
    Ok(created)
}

fn ensure_ovs_runtime_dir(topology: &CompiledTopology) -> Result<PathBuf, String> {
    use std::os::unix::fs::OpenOptionsExt;
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    ovs_owned_dir(Path::new("/run/nanpanel"), 0o755)?;
    ovs_owned_dir(Path::new("/run/nanpanel/topology-ovs"), 0o700)?;
    let directory = ovs_run_dir(topology);
    let created = ovs_owned_dir(&directory, 0o700)?;
    fs::set_permissions(&directory, fs::Permissions::from_mode(0o700))
        .map_err(|_| "FAILED: cannot secure OVS topology directory".to_owned())?;
    let owner_file = directory.join("owner");
    if !created && !owner_file.exists() {
        return Err(
            "DENIED: refusing to claim an OVS runtime directory without its ownership marker"
                .to_owned(),
        );
    }
    match OpenOptions::new()
        .write(true)
        .create_new(true)
        .mode(0o600)
        .open(&owner_file)
    {
        Ok(mut file) => {
            file.write_all(ovs_owner(topology).as_bytes())
                .and_then(|()| file.sync_all())
                .map_err(|_| "FAILED: cannot write OVS ownership marker".to_owned())?;
        }
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            if read_ovs_owner(topology)?.as_deref() != Some(ovs_owner(topology).as_str()) {
                return Err(
                    "DENIED: OVS runtime directory belongs to another topology revision".to_owned(),
                );
            }
        }
        Err(_) => return Err("FAILED: cannot create OVS ownership marker".to_owned()),
    }
    let metadata = fs::symlink_metadata(&owner_file)
        .map_err(|_| "FAILED: cannot inspect OVS ownership marker".to_owned())?;
    if !metadata.file_type().is_file() || metadata.uid() != 0 || metadata.mode() & 0o077 != 0 {
        return Err("DENIED: OVS ownership marker is not a private root-owned file".to_owned());
    }
    Ok(directory)
}

fn read_ovs_owner(topology: &CompiledTopology) -> Result<Option<String>, String> {
    use std::os::unix::fs::MetadataExt;
    let directory = ovs_run_dir(topology);
    let Ok(directory_metadata) = fs::symlink_metadata(&directory) else {
        return Ok(None);
    };
    if !directory_metadata.file_type().is_dir()
        || directory_metadata.uid() != 0
        || directory_metadata.mode() & 0o077 != 0
    {
        return Err("DENIED: OVS topology directory is not protected".to_owned());
    }
    let owner_file = directory.join("owner");
    let metadata = fs::symlink_metadata(&owner_file)
        .map_err(|_| "DENIED: OVS topology ownership marker is missing".to_owned())?;
    if !metadata.file_type().is_file() || metadata.uid() != 0 || metadata.mode() & 0o077 != 0 {
        return Err("DENIED: OVS topology ownership marker is not protected".to_owned());
    }
    fs::read_to_string(owner_file)
        .map(Some)
        .map_err(|_| "FAILED: cannot read OVS topology ownership marker".to_owned())
}

fn ovs_paths(topology: &CompiledTopology) -> (PathBuf, PathBuf, PathBuf, PathBuf, PathBuf) {
    let directory = ovs_run_dir(topology);
    (
        directory.join("conf.db"),
        directory.join("db.sock"),
        directory.join("db.pid"),
        directory.join("db.ctl"),
        directory.join("switch.ctl"),
    )
}

fn path_arg(path: &Path) -> Result<String, String> {
    path.to_str()
        .map(str::to_owned)
        .ok_or_else(|| "DENIED: OVS runtime path is invalid".to_owned())
}

async fn ovs_vsctl(topology: &CompiledTopology, args: &[String]) -> Result<String, String> {
    let ovs_vsctl = trusted_executable(&["/usr/sbin/ovs-vsctl", "/usr/bin/ovs-vsctl"])?;
    let (_, socket, _, _, _) = ovs_paths(topology);
    let mut command = vec![
        format!("--db=unix:{}", path_arg(&socket)?),
        "--timeout=5".to_owned(),
    ];
    command.extend_from_slice(args);
    run_fixed_command(&ovs_vsctl, &command, NETWORK_TIMEOUT).await
}

async fn ovs_read(topology: &CompiledTopology, args: &[String]) -> Result<String, String> {
    ovs_vsctl(topology, args).await
}

fn ovs_value(output: &str) -> String {
    let output = output.trim();
    serde_json::from_str::<String>(output).unwrap_or_else(|_| output.to_owned())
}

fn ovs_bridge_owner(topology: &CompiledTopology, node_id: Uuid) -> String {
    format!("{}:{}", ovs_owner(topology), node_id)
}

fn ovs_port_owner(topology: &CompiledTopology, link_id: Uuid, node_id: Uuid, side: &str) -> String {
    format!("{}:{}:{}:{}", ovs_owner(topology), link_id, node_id, side)
}

async fn start_ovs(topology: &CompiledTopology) -> Result<(), String> {
    if !topology.uses_ovs() {
        return Ok(());
    }
    let directory = ensure_ovs_runtime_dir(topology)?;
    let schema =
        ovs_schema().ok_or_else(|| "DENIED: trusted OVS schema is unavailable".to_owned())?;
    let (database, socket, db_pid, db_ctl, switch_ctl) = ovs_paths(topology);
    let db_log = directory.join("db.log");
    let switch_pid = directory.join("switch.pid");
    let switch_log = directory.join("switch.log");
    let ovsdb_tool = trusted_executable(&["/usr/sbin/ovsdb-tool", "/usr/bin/ovsdb-tool"])?;
    run_fixed_command(
        &ovsdb_tool,
        &[
            "create".to_owned(),
            path_arg(&database)?,
            path_arg(&schema)?,
        ],
        NETWORK_TIMEOUT,
    )
    .await?;
    let ovsdb_server = trusted_executable(&["/usr/sbin/ovsdb-server", "/usr/bin/ovsdb-server"])?;
    in_namespace(
        &ovsdb_server,
        &topology.namespace,
        &[
            format!("--remote=punix:{}", path_arg(&socket)?),
            format!("--pidfile={}", path_arg(&db_pid)?),
            format!("--unixctl={}", path_arg(&db_ctl)?),
            format!("--log-file={}", path_arg(&db_log)?),
            "--detach".to_owned(),
            "--no-chdir".to_owned(),
            path_arg(&database)?,
        ],
    )
    .await?;
    let mut initialized = false;
    for _ in 0..50 {
        if ovs_vsctl(topology, &["--no-wait".to_owned(), "init".to_owned()])
            .await
            .is_ok()
        {
            initialized = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    if !initialized {
        return Err("FAILED: private OVSDB did not become ready".to_owned());
    }
    let ovs_vswitchd = trusted_executable(&["/usr/sbin/ovs-vswitchd", "/usr/bin/ovs-vswitchd"])?;
    in_namespace(
        &ovs_vswitchd,
        &topology.namespace,
        &[
            format!("--pidfile={}", path_arg(&switch_pid)?),
            format!("--unixctl={}", path_arg(&switch_ctl)?),
            format!("--log-file={}", path_arg(&switch_log)?),
            "--detach".to_owned(),
            "--no-chdir".to_owned(),
            format!("unix:{}", path_arg(&socket)?),
        ],
    )
    .await?;
    let ovs_appctl = trusted_executable(&["/usr/sbin/ovs-appctl", "/usr/bin/ovs-appctl"])?;
    let mut running = false;
    for _ in 0..50 {
        if run_fixed_command(
            &ovs_appctl,
            &[
                "-t".to_owned(),
                path_arg(&switch_ctl)?,
                "version".to_owned(),
            ],
            Duration::from_secs(2),
        )
        .await
        .is_ok()
        {
            running = true;
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    if !running {
        return Err("FAILED: private ovs-vswitchd did not become ready".to_owned());
    }
    Ok(())
}

async fn stop_ovs_daemon(appctl: &Path, control: &Path, pid: &Path) -> Result<(), String> {
    if !pid.exists() {
        return Ok(());
    }
    if !control.exists() {
        return Err("FAILED: OVS daemon PID exists without its private control socket".to_owned());
    }
    run_fixed_command(
        appctl,
        &["-t".to_owned(), path_arg(control)?, "exit".to_owned()],
        NETWORK_TIMEOUT,
    )
    .await?;
    for _ in 0..100 {
        if !pid.exists() {
            return Ok(());
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    Err("UNCERTAIN: OVS daemon did not stop after its private exit request".to_owned())
}

async fn cleanup_ovs(topology: &CompiledTopology) -> Result<(), String> {
    if !topology.uses_ovs() {
        return Ok(());
    }
    let Some(owner) = read_ovs_owner(topology)? else {
        return Ok(());
    };
    if owner != ovs_owner(topology) {
        return Err(
            "DENIED: refusing to stop OVS daemons owned by another topology revision".to_owned(),
        );
    }
    let appctl = trusted_executable(&["/usr/sbin/ovs-appctl", "/usr/bin/ovs-appctl"])?;
    let (_, _, db_pid, db_ctl, switch_ctl) = ovs_paths(topology);
    let switch_pid = ovs_run_dir(topology).join("switch.pid");
    stop_ovs_daemon(&appctl, &switch_ctl, &switch_pid).await?;
    stop_ovs_daemon(&appctl, &db_ctl, &db_pid).await?;
    fs::remove_dir_all(ovs_run_dir(topology))
        .map_err(|_| "FAILED: cannot remove stopped OVS topology runtime".to_owned())
}

async fn add_ovs_bridge(topology: &CompiledTopology, node_id: Uuid) -> Result<(), String> {
    let bridge = topology
        .bridges
        .get(&node_id)
        .ok_or_else(|| "DENIED: OVS switch bridge name is missing".to_owned())?;
    let owner = ovs_bridge_owner(topology, node_id);
    ovs_vsctl(
        topology,
        &[
            "add-br".to_owned(),
            bridge.clone(),
            "--".to_owned(),
            "set".to_owned(),
            "Bridge".to_owned(),
            bridge.clone(),
            "datapath_type=netdev".to_owned(),
            "other_config:disable-in-band=true".to_owned(),
            format!("external_ids:nanpanel-owner={owner}"),
        ],
    )
    .await?;
    Ok(())
}

async fn add_ovs_port(
    topology: &CompiledTopology,
    node_id: Uuid,
    interface: &str,
    link_id: Uuid,
    side: &str,
) -> Result<(), String> {
    let bridge = topology
        .bridges
        .get(&node_id)
        .ok_or_else(|| "DENIED: OVS switch bridge name is missing".to_owned())?;
    ovs_vsctl(
        topology,
        &[
            "add-port".to_owned(),
            bridge.clone(),
            interface.to_owned(),
            "--".to_owned(),
            "set".to_owned(),
            "Port".to_owned(),
            interface.to_owned(),
            format!(
                "external_ids:nanpanel-owner={}",
                ovs_port_owner(topology, link_id, node_id, side)
            ),
        ],
    )
    .await?;
    Ok(())
}

async fn verify_ovs_bridge(topology: &CompiledTopology, node_id: Uuid) -> Result<bool, String> {
    let Some(owner) = read_ovs_owner(topology)? else {
        return Ok(false);
    };
    if owner != ovs_owner(topology) {
        return Ok(false);
    }
    let appctl = trusted_executable(&["/usr/sbin/ovs-appctl", "/usr/bin/ovs-appctl"])?;
    let (_, _, db_pid, db_ctl, switch_ctl) = ovs_paths(topology);
    let switch_pid = ovs_run_dir(topology).join("switch.pid");
    if !db_pid.exists() || !switch_pid.exists() {
        return Ok(false);
    }
    for control in [&db_ctl, &switch_ctl] {
        if run_fixed_command(
            &appctl,
            &["-t".to_owned(), path_arg(control)?, "version".to_owned()],
            Duration::from_secs(2),
        )
        .await
        .is_err()
        {
            return Ok(false);
        }
    }
    let Some(bridge) = topology.bridges.get(&node_id) else {
        return Ok(false);
    };
    let datapath = ovs_read(
        topology,
        &[
            "get".to_owned(),
            "Bridge".to_owned(),
            bridge.clone(),
            "datapath_type".to_owned(),
        ],
    )
    .await?;
    let actual_owner = ovs_read(
        topology,
        &[
            "get".to_owned(),
            "Bridge".to_owned(),
            bridge.clone(),
            "external_ids:nanpanel-owner".to_owned(),
        ],
    )
    .await?;
    Ok(ovs_value(&datapath) == "netdev"
        && ovs_value(&actual_owner) == ovs_bridge_owner(topology, node_id))
}

async fn verify_ovs_ports(topology: &CompiledTopology) -> Result<bool, String> {
    for node in topology
        .request
        .nodes
        .iter()
        .filter(|node| topology.is_ovs_switch(node.id))
    {
        let Some(bridge) = topology.bridges.get(&node.id) else {
            return Ok(false);
        };
        let mut expected_interfaces = HashSet::new();
        for link in &topology.request.links {
            if link.source_node_id == node.id {
                let Some((interface, _)) = topology.interfaces.get(&link.id) else {
                    return Ok(false);
                };
                expected_interfaces.insert(interface.clone());
            } else if link.target_node_id == node.id {
                let Some((_, interface)) = topology.interfaces.get(&link.id) else {
                    return Ok(false);
                };
                expected_interfaces.insert(interface.clone());
            }
        }
        let output = ovs_read(topology, &["list-ports".to_owned(), bridge.clone()]).await?;
        let actual_interfaces = output
            .lines()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_owned)
            .collect::<HashSet<_>>();
        if actual_interfaces != expected_interfaces {
            return Ok(false);
        }
        for link in &topology.request.links {
            let (interface, side) = if link.source_node_id == node.id {
                (&topology.interfaces[&link.id].0, "source")
            } else if link.target_node_id == node.id {
                (&topology.interfaces[&link.id].1, "target")
            } else {
                continue;
            };
            let owner = ovs_read(
                topology,
                &[
                    "get".to_owned(),
                    "Port".to_owned(),
                    interface.clone(),
                    "external_ids:nanpanel-owner".to_owned(),
                ],
            )
            .await?;
            if ovs_value(&owner) != ovs_port_owner(topology, link.id, node.id, side) {
                return Ok(false);
            }
        }
    }
    Ok(true)
}

async fn apply(topology: &CompiledTopology) -> Result<String, String> {
    let ip = ip_binary()?;
    if namespace_exists(&ip, &topology.namespace).await? {
        return match verify(topology).await {
            Ok(result) => Ok(result),
            Err(_) => Err("FAILED: this topology revision already has a namespace with drift; verify or compensate it before retrying".to_owned()),
        };
    }

    cleanup_ovs(topology).await?;

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
    if let Err(error) = result {
        if let Err(cleanup_error) = cleanup_ovs(topology).await {
            return Err(format!("{error}; OVS cleanup failed: {cleanup_error}"));
        }
        if let Err(namespace_error) = run_fixed_command(
            &ip,
            &[
                "netns".to_owned(),
                "del".to_owned(),
                topology.namespace.clone(),
            ],
            NETWORK_TIMEOUT,
        )
        .await
        {
            return Err(format!(
                "{error}; namespace cleanup failed: {namespace_error}"
            ));
        }
        return Err(error);
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
    start_ovs(topology).await?;
    for node in &topology.request.nodes {
        let bridge = &topology.bridges[&node.id];
        if topology.is_ovs_switch(node.id) {
            add_ovs_bridge(topology, node.id).await?;
            continue;
        }
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
            if topology.is_ovs_switch(node_id) {
                add_ovs_port(topology, node_id, interface, link.id, side).await?;
            } else if !mirror_output && !topology.is_router(node_id) {
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
    configure_routers(topology).await?;

    verify(topology).await
}

async fn configure_routers(topology: &CompiledTopology) -> Result<(), String> {
    if topology.routers.is_empty() {
        return Ok(());
    }
    let ip = ip_binary()?;
    let sysctl = sysctl_binary()?;
    for router in &topology.routers {
        for address in &router.interfaces {
            in_namespace(
                &ip,
                &topology.namespace,
                &[
                    "address".to_owned(),
                    "replace".to_owned(),
                    address.address.clone(),
                    "dev".to_owned(),
                    address.interface.clone(),
                ],
            )
            .await?;
        }
        in_namespace(
            &sysctl,
            &topology.namespace,
            &["-w".to_owned(), "net.ipv4.ip_forward=1".to_owned()],
        )
        .await?;
        for route in &router.routes {
            let mut args = vec![
                "route".to_owned(),
                "replace".to_owned(),
                route.destination.clone(),
            ];
            if let Some(gateway) = &route.gateway {
                args.extend(["via".to_owned(), gateway.clone()]);
            }
            args.extend([
                "dev".to_owned(),
                route.interface.clone(),
                "proto".to_owned(),
                "static".to_owned(),
            ]);
            in_namespace(&ip, &topology.namespace, &args).await?;
        }
    }
    Ok(())
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
    let default_routes = serde_json::from_str::<Value>(&route_output)
        .map_err(|_| "UNCERTAIN: network namespace route output is invalid".to_owned())?;
    let expected_default_routes = topology
        .routers
        .iter()
        .flat_map(|router| &router.routes)
        .filter(|route| route.destination == "0.0.0.0/0")
        .count();
    if default_routes
        .as_array()
        .is_none_or(|routes| routes.len() != expected_default_routes)
    {
        return Err(
            "FAILED: isolated topology namespace has an undeclared default route".to_owned(),
        );
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
        if topology.is_ovs_switch(node.id) {
            if !verify_ovs_bridge(topology, node.id).await? {
                return Err(format!(
                    "FAILED: OVS switch {} bridge or private daemon is missing or has drifted",
                    node.id
                ));
            }
            continue;
        }
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
    if !verify_ovs_ports(topology).await? {
        return Err("FAILED: OVS bridge ports or ownership markers have drifted".to_owned());
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
            let expected_master = if topology.is_monitor_interface(interface)
                || topology.is_router(node_id)
                || topology.is_ovs_switch(node_id)
            {
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
    let route_output = in_namespace(
        &ip,
        &topology.namespace,
        &[
            "-j".to_owned(),
            "route".to_owned(),
            "show".to_owned(),
            "table".to_owned(),
            "main".to_owned(),
        ],
    )
    .await?;
    let routes = serde_json::from_str::<Vec<Value>>(&route_output)
        .map_err(|_| "UNCERTAIN: topology IPv4 route output is invalid".to_owned())?;
    for router in &topology.routers {
        if !router_addresses_match(router, &ip, &topology.namespace).await? {
            return Err(format!(
                "FAILED: router {} interface addresses have drifted",
                router.node_id
            ));
        }
        let forwarding = in_namespace(
            &sysctl_binary()?,
            &topology.namespace,
            &["-n".to_owned(), "net.ipv4.ip_forward".to_owned()],
        )
        .await?;
        if forwarding.trim() != "1" {
            return Err(format!(
                "FAILED: router {} IPv4 forwarding is disabled",
                router.node_id
            ));
        }
    }
    if !router_routes_match(&routes, &topology.routers) {
        return Err("FAILED: isolated Router static routes are missing or have drifted".to_owned());
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
        "routers": topology.routers.len(),
        "packetTaps": topology.packet_taps.len(),
        "firewalls": topology.firewalls.len(),
        "wires": topology.request.links.len(),
        "linkMetrics": link_metrics,
        "defaultRoutes": expected_default_routes,
    })
    .to_string())
}

async fn router_addresses_match(
    router: &Router,
    ip: &Path,
    namespace: &str,
) -> Result<bool, String> {
    for expected in &router.interfaces {
        let output = in_namespace(
            ip,
            namespace,
            &[
                "-j".to_owned(),
                "address".to_owned(),
                "show".to_owned(),
                "dev".to_owned(),
                expected.interface.clone(),
            ],
        )
        .await?;
        let Ok(devices) = serde_json::from_str::<Vec<Value>>(&output) else {
            return Ok(false);
        };
        let expected_parts = expected.address.split_once('/');
        let Some((expected_address, expected_prefix)) = expected_parts else {
            return Ok(false);
        };
        let Ok(expected_prefix) = expected_prefix.parse::<u64>() else {
            return Ok(false);
        };
        let addresses = devices
            .iter()
            .filter_map(|device| device.get("addr_info").and_then(Value::as_array))
            .flat_map(|values| values.iter())
            .filter(|address| address.get("family").and_then(Value::as_str) == Some("inet"))
            .collect::<Vec<_>>();
        if addresses.len() != 1
            || !addresses.first().is_some_and(|address| {
                address.get("local").and_then(Value::as_str) == Some(expected_address)
                    && address.get("prefixlen").and_then(Value::as_u64) == Some(expected_prefix)
            })
        {
            return Ok(false);
        }
    }
    Ok(true)
}

fn router_routes_match(actual: &[Value], routers: &[Router]) -> bool {
    let expected_count = routers
        .iter()
        .map(|router| router.routes.len())
        .sum::<usize>();
    let static_routes = actual
        .iter()
        .filter(|route| {
            route.get("protocol").and_then(Value::as_str) == Some("static")
                || route.get("proto").and_then(Value::as_str) == Some("static")
        })
        .collect::<Vec<_>>();
    if static_routes.len() != expected_count {
        return false;
    }
    let expected_defaults = routers
        .iter()
        .flat_map(|router| &router.routes)
        .filter(|route| route.destination == "0.0.0.0/0")
        .count();
    let actual_defaults = actual
        .iter()
        .filter(|route| {
            matches!(
                route.get("dst").and_then(Value::as_str),
                None | Some("default") | Some("0.0.0.0/0")
            )
        })
        .count();
    if actual_defaults != expected_defaults {
        return false;
    }
    routers
        .iter()
        .flat_map(|router| &router.routes)
        .all(|expected| {
            static_routes.iter().any(|actual| {
                let actual_destination = match actual.get("dst").and_then(Value::as_str) {
                    None | Some("default") => "0.0.0.0/0",
                    Some(destination) => destination,
                };
                let actual_gateway = actual.get("gateway").and_then(Value::as_str);
                let actual_device = actual.get("dev").and_then(Value::as_str);
                actual_destination == expected.destination
                    && actual_gateway == expected.gateway.as_deref()
                    && actual_device == Some(expected.interface.as_str())
            })
        })
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
    let exists = namespace_exists(&ip, &topology.namespace).await?;
    if exists && !namespace_owned(topology, &ip).await? {
        return Err(
            "DENIED: refusing to remove a namespace without this topology's ownership marker"
                .to_owned(),
        );
    }
    cleanup_ovs(topology).await?;
    if exists {
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
    if topology.uses_ovs() {
        return Ok(read_ovs_owner(topology)?.as_deref() == Some(ovs_owner(topology).as_str()));
    }
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
        Router, RouterRoute, SwitchBackend, compile, decode, firewall_rules_match,
        has_packet_mirror, interface_statistics, router_routes_match, validate_target_payload,
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
    fn decodes_ovs_backend_and_rejects_mixing_it_with_packet_tap() -> anyhow::Result<()> {
        let (target, payload) = sample();
        let mut value = serde_json::from_str::<serde_json::Value>(&payload)?;
        value["nodes"][0]["switchBackend"] = json!("OPENVSWITCH");
        let decoded = decode(&value.to_string()).map_err(anyhow::Error::msg)?;
        assert_eq!(
            decoded.nodes[0].switch_backend,
            Some(SwitchBackend::Openvswitch)
        );

        value["nodes"][1]["kind"] = json!("PACKET_TAP");
        let error = compile(
            &target,
            decode(&value.to_string()).map_err(anyhow::Error::msg)?,
        )
        .expect_err("OVS and Packet Tap must not share a topology revision");
        assert!(error.contains("cannot include Firewall or Packet Tap"));
        value["nodes"][0]["switchBackend"] = json!("UNSUPPORTED");
        assert!(decode(&value.to_string()).is_err());
        Ok(())
    }

    #[test]
    fn ovs_verify_and_compensate_compile_without_apply_prerequisites() -> anyhow::Result<()> {
        let (target, payload) = sample();
        let mut value = serde_json::from_str::<serde_json::Value>(&payload)?;
        value["nodes"][0]["switchBackend"] = json!("OPENVSWITCH");
        for action in ["VERIFY", "COMPENSATE"] {
            value["action"] = json!(action);
            compile(
                &target,
                decode(&value.to_string()).map_err(anyhow::Error::msg)?,
            )
            .map_err(anyhow::Error::msg)?;
        }

        value["action"] = json!("APPLY");
        let result = compile(
            &target,
            decode(&value.to_string()).map_err(anyhow::Error::msg)?,
        );
        if super::ovs_available() {
            result.map_err(anyhow::Error::msg)?;
        } else {
            assert!(result.is_err_and(|error| error.contains("trusted OVS userspace tools")));
        }
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

    #[test]
    fn compiles_a_single_isolated_ipv4_router_with_connected_interfaces_and_routes()
    -> anyhow::Result<()> {
        let topology_id = Uuid::new_v4();
        let source = Uuid::new_v4();
        let router = Uuid::new_v4();
        let target = Uuid::new_v4();
        let mut payload = json!({
            "action": "APPLY",
            "topologyId": topology_id,
            "revisionId": Uuid::new_v4(),
            "generation": 9,
            "nodes": [
                {"id": source, "kind": "SWITCH"},
                {"id": router, "kind": "ROUTER", "routerConfiguration": {
                    "interfaces": [
                        {"port": "in-1", "address": "198.18.1.1/24"},
                        {"port": "out-1", "address": "198.18.2.1/24"}
                    ],
                    "routes": [{"destination": "198.18.3.0/24", "gateway": "198.18.2.2", "outputPort": "out-1"}]
                }},
                {"id": target, "kind": "SWITCH"}
            ],
            "links": [
                {"id": Uuid::new_v4(), "sourceNodeId": source, "sourcePort": "out-1",
                 "targetNodeId": router, "targetPort": "in-1", "layer": "L2", "protocol": "ETHERNET"},
                {"id": Uuid::new_v4(), "sourceNodeId": router, "sourcePort": "out-1",
                 "targetNodeId": target, "targetPort": "in-1", "layer": "L2", "protocol": "ETHERNET"}
            ]
        });
        let compiled = compile(
            &topology_id.to_string(),
            decode(&payload.to_string()).map_err(anyhow::Error::msg)?,
        )
        .map_err(anyhow::Error::msg)?;
        assert_eq!(compiled.routers.len(), 1);
        let compiled_router = compiled
            .routers
            .first()
            .ok_or_else(|| anyhow::anyhow!("compiled router is missing"))?;
        assert_eq!(compiled_router.interfaces.len(), 2);
        assert_eq!(compiled_router.routes.len(), 1);
        assert!(
            compiled_router
                .interfaces
                .iter()
                .any(|address| address.interface == compiled_router.routes[0].interface)
        );
        assert_eq!(
            compiled_router.routes[0].gateway.as_deref(),
            Some("198.18.2.2")
        );

        payload["nodes"][1]["routerConfiguration"]["routes"][0]["gateway"] = json!("198.18.4.2");
        assert!(
            validate_target_payload(&topology_id.to_string(), Some(&payload.to_string())).is_err()
        );
        payload["nodes"][1]["routerConfiguration"]["routes"][0]["gateway"] = json!("198.18.2.2");
        payload["nodes"]
            .as_array_mut()
            .ok_or_else(|| anyhow::anyhow!("nodes are missing"))?
            .push(json!({
                "id": Uuid::new_v4(), "kind": "ROUTER", "routerConfiguration": {
                    "interfaces": [{"port": "in-1", "address": "198.18.4.1/24"}], "routes": []
                }
            }));
        assert!(
            validate_target_payload(&topology_id.to_string(), Some(&payload.to_string())).is_err()
        );
        Ok(())
    }

    #[test]
    fn verifies_exact_router_static_routes_and_rejects_an_extra_default_route() {
        let expected = [Router {
            node_id: Uuid::new_v4(),
            interfaces: vec![],
            routes: vec![RouterRoute {
                destination: "0.0.0.0/0".to_owned(),
                gateway: Some("198.18.2.2".to_owned()),
                interface: "npl00a".to_owned(),
            }],
        }];
        let actual = vec![json!({
            "dst": "default", "gateway": "198.18.2.2", "dev": "npl00a", "protocol": "static"
        })];
        assert!(router_routes_match(&actual, &expected));
        let mut drifted = actual.clone();
        drifted.push(json!({
            "dst": "default", "gateway": "198.18.2.254", "dev": "npl00a", "protocol": "boot"
        }));
        assert!(!router_routes_match(&drifted, &expected));
    }
}
