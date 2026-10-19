use std::{
    collections::{BTreeSet, HashMap, HashSet},
    io,
    net::{IpAddr, Ipv4Addr, SocketAddr},
    sync::atomic::{AtomicU16, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

use anyhow::{Context, Result, bail};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpStream, UdpSocket},
    time,
};

const DNS_SERVERS: [SocketAddr; 2] = [
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)), 53),
    SocketAddr::new(IpAddr::V4(Ipv4Addr::new(1, 0, 0, 1)), 53),
];
const DNS_TIMEOUT: Duration = Duration::from_secs(2);
const MAX_DNS_PACKET: usize = 4096;
const MAX_CNAME_HOPS: usize = 8;
const QUERY_A: u16 = 1;
const QUERY_CNAME: u16 = 5;
const QUERY_AAAA: u16 = 28;
static QUERY_SEQUENCE: AtomicU16 = AtomicU16::new(1);

/// Resolve a service name directly through the two configured Cloudflare public resolvers.
/// Each resolver is asked for A and AAAA in each of at most three bounded rounds.
pub async fn resolve_cloudflare(host: &str) -> Result<Vec<IpAddr>> {
    let host = normalize_name(host)?;
    for round in 0..3 {
        let mut addresses = BTreeSet::new();
        let (first_a, first_aaaa, second_a, second_aaaa) = tokio::join!(
            query_server(DNS_SERVERS[0], &host, QUERY_A),
            query_server(DNS_SERVERS[0], &host, QUERY_AAAA),
            query_server(DNS_SERVERS[1], &host, QUERY_A),
            query_server(DNS_SERVERS[1], &host, QUERY_AAAA)
        );
        for found in [first_a, first_aaaa, second_a, second_aaaa]
            .into_iter()
            .flatten()
        {
            addresses.extend(found.into_iter().filter(is_public_unicast));
        }
        if !addresses.is_empty() {
            return Ok(addresses.into_iter().collect());
        }
        if round < 2 {
            let delay = Duration::from_secs(round + 1) + jitter();
            time::sleep(delay).await;
        }
    }
    bail!("direct Cloudflare DNS lookup returned no usable A or AAAA records")
}

async fn query_server(server: SocketAddr, name: &str, record_type: u16) -> Result<Vec<IpAddr>> {
    let query_id = next_query_id();
    let query = build_query(query_id, name, record_type)?;
    let udp_result = time::timeout(DNS_TIMEOUT, udp_query(server, &query)).await;
    let response = match udp_result {
        Ok(Ok(packet)) if packet.get(2).is_some_and(|flags| *flags & 0x02 != 0) => {
            time::timeout(DNS_TIMEOUT, tcp_query(server, &query)).await??
        }
        Ok(Ok(packet)) => packet,
        Ok(Err(error)) => return Err(error).context("send direct DNS query over UDP"),
        Err(error) => return Err(error).context("direct DNS UDP query timed out"),
    };
    parse_answer(&response, query_id, name, record_type)
}

async fn udp_query(server: SocketAddr, query: &[u8]) -> Result<Vec<u8>> {
    let socket = UdpSocket::bind("0.0.0.0:0")
        .await
        .context("bind direct DNS UDP socket")?;
    socket
        .connect(server)
        .await
        .context("connect direct DNS UDP socket")?;
    socket
        .send(query)
        .await
        .context("send direct DNS UDP request")?;
    let mut response = vec![0; MAX_DNS_PACKET];
    let length = socket
        .recv(&mut response)
        .await
        .context("receive direct DNS UDP response")?;
    response.truncate(length);
    Ok(response)
}

async fn tcp_query(server: SocketAddr, query: &[u8]) -> Result<Vec<u8>> {
    let mut socket = TcpStream::connect(server)
        .await
        .context("connect direct DNS TCP fallback")?;
    let query_length = u16::try_from(query.len()).context("DNS request is too large")?;
    socket
        .write_all(&query_length.to_be_bytes())
        .await
        .context("write direct DNS TCP frame length")?;
    socket
        .write_all(query)
        .await
        .context("write direct DNS TCP request")?;
    let response_length = socket
        .read_u16()
        .await
        .context("read direct DNS TCP frame length")?;
    if usize::from(response_length) > MAX_DNS_PACKET {
        bail!("direct DNS TCP response exceeds its size limit")
    }
    let mut response = vec![0; usize::from(response_length)];
    socket
        .read_exact(&mut response)
        .await
        .context("read direct DNS TCP response")?;
    Ok(response)
}

fn parse_answer(
    packet: &[u8],
    expected_id: u16,
    queried_name: &str,
    queried_type: u16,
) -> Result<Vec<IpAddr>> {
    if packet.len() < 12 || packet.len() > MAX_DNS_PACKET {
        bail!("direct DNS response has an invalid size")
    }
    if read_u16(packet, 0)? != expected_id {
        bail!("direct DNS response transaction ID does not match")
    }
    let flags = read_u16(packet, 2)?;
    if flags & 0x8000 == 0 || flags & 0x7800 != 0 {
        bail!("direct DNS response is not a standard response")
    }
    if flags & 0x0200 != 0 {
        bail!("direct DNS TCP response is still truncated")
    }
    if flags & 0x000f != 0 {
        bail!("direct DNS response returned a non-zero result code")
    }
    let question_count = usize::from(read_u16(packet, 4)?);
    let answer_count = usize::from(read_u16(packet, 6)?);
    let authority_count = usize::from(read_u16(packet, 8)?);
    let additional_count = usize::from(read_u16(packet, 10)?);
    if question_count != 1 || answer_count > 128 || authority_count > 128 || additional_count > 128
    {
        bail!("direct DNS response section count exceeds its limit")
    }
    let mut cursor = 12;
    let (question_name, after_question_name) = decode_name(packet, cursor)?;
    cursor = after_question_name;
    let question_type = read_u16(packet, cursor)?;
    let question_class = read_u16(packet, cursor + 2)?;
    cursor += 4;
    if question_name != queried_name || question_type != queried_type || question_class != 1 {
        bail!("direct DNS response question does not match the request")
    }

    let mut records = Vec::with_capacity(answer_count);
    for index in 0..answer_count + authority_count + additional_count {
        let (owner, after_owner) = decode_name(packet, cursor)?;
        cursor = after_owner;
        let record_type = read_u16(packet, cursor)?;
        let record_class = read_u16(packet, cursor + 2)?;
        let data_length = usize::from(read_u16(packet, cursor + 8)?);
        cursor += 10;
        let data_end = cursor
            .checked_add(data_length)
            .filter(|end| *end <= packet.len())
            .context("direct DNS resource record exceeds the response size")?;
        if index < answer_count && record_class == 1 {
            records.push(Record {
                owner: normalize_name(&owner)?,
                record_type,
                data_start: cursor,
                data_length,
            });
        }
        cursor = data_end;
    }
    if cursor != packet.len() {
        bail!("direct DNS response has trailing data")
    }

    let mut aliases = HashMap::new();
    for record in &records {
        if record.record_type != QUERY_CNAME {
            continue;
        }
        let (target, consumed) = decode_name(packet, record.data_start)?;
        if consumed != record.data_start + record.data_length {
            bail!("direct DNS CNAME value exceeds its resource record")
        }
        aliases.insert(record.owner.clone(), normalize_name(&target)?);
    }
    let mut chain = HashSet::new();
    let mut current = queried_name.to_owned();
    for _ in 0..=MAX_CNAME_HOPS {
        if !chain.insert(current.clone()) {
            bail!("direct DNS CNAME chain contains a loop")
        }
        let Some(target) = aliases.get(&current) else {
            break;
        };
        current = target.clone();
    }
    if aliases.contains_key(&current) {
        bail!("direct DNS CNAME chain exceeds its hop limit")
    }

    let mut addresses = BTreeSet::new();
    for record in records {
        if !chain.contains(&record.owner) || record.record_type != queried_type {
            continue;
        }
        match (record.record_type, record.data_length) {
            (QUERY_A, 4) => {
                let bytes: [u8; 4] = packet[record.data_start..record.data_start + 4]
                    .try_into()
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid A record"))?;
                addresses.insert(IpAddr::V4(Ipv4Addr::from(bytes)));
            }
            (QUERY_AAAA, 16) => {
                let bytes: [u8; 16] = packet[record.data_start..record.data_start + 16]
                    .try_into()
                    .map_err(|_| {
                        io::Error::new(io::ErrorKind::InvalidData, "invalid AAAA record")
                    })?;
                addresses.insert(IpAddr::from(bytes));
            }
            _ => bail!("direct DNS response contains an address with an invalid size"),
        }
    }
    Ok(addresses.into_iter().collect())
}

struct Record {
    owner: String,
    record_type: u16,
    data_start: usize,
    data_length: usize,
}

fn build_query(query_id: u16, name: &str, record_type: u16) -> Result<Vec<u8>> {
    let name = normalize_name(name)?;
    let mut packet = Vec::with_capacity(name.len() + 18);
    packet.extend_from_slice(&query_id.to_be_bytes());
    packet.extend_from_slice(&0x0100_u16.to_be_bytes());
    packet.extend_from_slice(&1_u16.to_be_bytes());
    packet.extend_from_slice(&0_u16.to_be_bytes());
    packet.extend_from_slice(&0_u16.to_be_bytes());
    packet.extend_from_slice(&0_u16.to_be_bytes());
    for label in name.split('.') {
        packet.push(u8::try_from(label.len()).context("DNS label is too long")?);
        packet.extend_from_slice(label.as_bytes());
    }
    packet.push(0);
    packet.extend_from_slice(&record_type.to_be_bytes());
    packet.extend_from_slice(&1_u16.to_be_bytes());
    Ok(packet)
}

fn normalize_name(name: &str) -> Result<String> {
    let normalized = name.trim_end_matches('.').to_ascii_lowercase();
    if normalized.is_empty()
        || normalized.len() > 253
        || normalized.split('.').any(|label| {
            label.is_empty()
                || label.len() > 63
                || !label
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
                || label.starts_with('-')
                || label.ends_with('-')
        })
    {
        bail!("DNS name is invalid")
    }
    Ok(normalized)
}

fn is_public_unicast(address: &IpAddr) -> bool {
    match address {
        IpAddr::V4(address) => {
            let [first, second, third, _] = address.octets();
            !(first == 0
                || first == 10
                || first == 127
                || (first == 100 && (64..=127).contains(&second))
                || (first == 169 && second == 254)
                || (first == 172 && (16..=31).contains(&second))
                || (first == 192 && second == 0 && (third == 0 || third == 2))
                || (first == 192 && second == 88 && third == 99)
                || (first == 192 && second == 168)
                || (first == 198 && (second == 18 || second == 19))
                || (first == 198 && second == 51 && third == 100)
                || (first == 203 && second == 0 && third == 113)
                || first >= 224)
        }
        IpAddr::V6(address) => {
            let segments = address.segments();
            let first = segments[0];
            let special_2001_range = first == 0x2001
                && ((0x0000..=0x01ff).contains(&segments[1]) || segments[1] == 0x0db8);
            (first & 0xe000) == 0x2000 && !special_2001_range && first != 0x2002
        }
    }
}

fn decode_name(packet: &[u8], offset: usize) -> Result<(String, usize)> {
    let mut labels = Vec::new();
    let mut cursor = offset;
    let mut end = None;
    let mut visited = HashSet::new();
    for _ in 0..128 {
        let length = *packet.get(cursor).context("DNS name exceeds the packet")?;
        if length & 0xc0 == 0xc0 {
            let second = *packet
                .get(cursor + 1)
                .context("DNS name compression pointer is truncated")?;
            let pointer = (usize::from(length & 0x3f) << 8) | usize::from(second);
            if pointer >= cursor || !visited.insert(pointer) {
                bail!("DNS name compression pointer is invalid or cyclic")
            }
            end.get_or_insert(cursor + 2);
            cursor = pointer;
            continue;
        }
        if length & 0xc0 != 0 {
            bail!("DNS name uses an unsupported label encoding")
        }
        cursor += 1;
        if length == 0 {
            return Ok((labels.join("."), end.unwrap_or(cursor)));
        }
        let label_end = cursor
            .checked_add(usize::from(length))
            .filter(|label_end| *label_end <= packet.len())
            .context("DNS label exceeds the packet")?;
        let label = std::str::from_utf8(&packet[cursor..label_end])
            .context("DNS label is not valid UTF-8")?
            .to_ascii_lowercase();
        labels.push(label);
        cursor = label_end;
    }
    bail!("DNS name contains too many labels or pointers")
}

fn read_u16(packet: &[u8], offset: usize) -> Result<u16> {
    let bytes = packet
        .get(offset..offset + 2)
        .context("DNS response field is truncated")?;
    Ok(u16::from_be_bytes([bytes[0], bytes[1]]))
}

fn next_query_id() -> u16 {
    let clock = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.subsec_nanos() as u16)
        .unwrap_or_default();
    QUERY_SEQUENCE.fetch_add(1, Ordering::Relaxed) ^ clock
}

fn jitter() -> Duration {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.subsec_nanos())
        .unwrap_or_default();
    Duration::from_millis(u64::from(nanos % 251))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn query_encoding_and_name_validation_are_bounded() -> Result<()> {
        let query = build_query(0x1234, "API.Example.Test.", QUERY_AAAA)?;
        assert_eq!(read_u16(&query, 0)?, 0x1234);
        assert_eq!(read_u16(&query, 2)?, 0x0100);
        assert_eq!(read_u16(&query, query.len() - 4)?, QUERY_AAAA);
        assert_eq!(normalize_name("API.Example.Test.")?, "api.example.test");
        assert!(normalize_name("-bad.example").is_err());
        assert!(normalize_name("bad..example").is_err());
        Ok(())
    }

    #[test]
    fn response_parser_follows_cname_and_checks_identity() -> Result<()> {
        let response = response_with_cname_a(0x4321);
        assert_eq!(
            parse_answer(&response, 0x4321, "api.example.test", QUERY_A)?,
            vec![IpAddr::V4(Ipv4Addr::new(203, 0, 113, 11))]
        );
        assert!(parse_answer(&response, 0x1234, "api.example.test", QUERY_A).is_err());
        assert!(parse_answer(&response, 0x4321, "other.example.test", QUERY_A).is_err());
        Ok(())
    }

    #[test]
    fn direct_dial_address_filter_rejects_private_and_reserved_ranges() -> Result<()> {
        for address in [
            IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)),
            IpAddr::V4(Ipv4Addr::new(10, 1, 2, 3)),
            IpAddr::V4(Ipv4Addr::new(100, 64, 0, 1)),
            IpAddr::V4(Ipv4Addr::new(203, 0, 113, 7)),
            IpAddr::V6("::1".parse()?),
            IpAddr::V6("fc00::1".parse()?),
            IpAddr::V6("2001:db8::1".parse()?),
        ] {
            assert!(
                !is_public_unicast(&address),
                "unexpectedly accepted {address}"
            );
        }
        for address in [
            IpAddr::V4(Ipv4Addr::new(1, 1, 1, 1)),
            IpAddr::V4(Ipv4Addr::new(203, 0, 114, 7)),
            IpAddr::V6("2606:4700::1".parse()?),
        ] {
            assert!(
                is_public_unicast(&address),
                "unexpectedly rejected {address}"
            );
        }
        Ok(())
    }

    fn response_with_cname_a(query_id: u16) -> Vec<u8> {
        let mut packet = Vec::new();
        packet.extend_from_slice(&query_id.to_be_bytes());
        packet.extend_from_slice(&0x8180_u16.to_be_bytes());
        packet.extend_from_slice(&1_u16.to_be_bytes());
        packet.extend_from_slice(&2_u16.to_be_bytes());
        packet.extend_from_slice(&0_u16.to_be_bytes());
        packet.extend_from_slice(&0_u16.to_be_bytes());
        encode_name(&mut packet, "api.example.test");
        packet.extend_from_slice(&QUERY_A.to_be_bytes());
        packet.extend_from_slice(&1_u16.to_be_bytes());

        packet.extend_from_slice(&[0xc0, 0x0c]);
        packet.extend_from_slice(&QUERY_CNAME.to_be_bytes());
        packet.extend_from_slice(&1_u16.to_be_bytes());
        packet.extend_from_slice(&60_u32.to_be_bytes());
        let mut cname = Vec::new();
        encode_name(&mut cname, "relay.example.test");
        packet.extend_from_slice(&(cname.len() as u16).to_be_bytes());
        packet.extend_from_slice(&cname);

        encode_name(&mut packet, "relay.example.test");
        packet.extend_from_slice(&QUERY_A.to_be_bytes());
        packet.extend_from_slice(&1_u16.to_be_bytes());
        packet.extend_from_slice(&60_u32.to_be_bytes());
        packet.extend_from_slice(&4_u16.to_be_bytes());
        packet.extend_from_slice(&[203, 0, 113, 11]);
        packet
    }

    fn encode_name(packet: &mut Vec<u8>, name: &str) {
        for label in name.split('.') {
            packet.push(label.len() as u8);
            packet.extend_from_slice(label.as_bytes());
        }
        packet.push(0);
    }
}
