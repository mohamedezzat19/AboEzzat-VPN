use crate::error::Result;
use hashlink::{LruCache, linked_hash_map::RawEntryMut};
use hickory_proto::{op::MessageType, rr::RecordType};
use std::{
    collections::HashMap,
    convert::TryInto,
    net::{IpAddr, Ipv4Addr, Ipv6Addr},
    time::{Duration, Instant},
};
use tproxy_config::IpCidr;

const MAPPING_TIMEOUT: u64 = 60; // Mapping timeout in seconds

struct NameCacheEntry {
    name: String,
    expiry: Instant,
}

/// A virtual DNS server which allocates IP addresses to clients.
/// The IP addresses are in the range of private IP addresses.
/// The DNS server is implemented as a LRU cache.
pub struct VirtualDns {
    trailing_dot: bool,
    lru_cache: LruCache<IpAddr, NameCacheEntry>,
    name_to_ip: HashMap<(String, bool), IpAddr>,
    ipv4_network_addr: IpAddr,
    ipv4_broadcast_addr: IpAddr,
    next_ipv4_addr: IpAddr,
    ipv6_network_addr: IpAddr,
    ipv6_broadcast_addr: IpAddr,
    next_ipv6_addr: IpAddr,
}

impl VirtualDns {
    pub fn new(ip_pool: IpCidr) -> Self {
        // Keep the configured pool for its address family and provide a
        // separate virtual pool for the other family. This lets Android
        // clients receive both A and AAAA answers while preserving one
        // hostname mapping across IPv4 and IPv6 connections.
        let ipv4_pool = if ip_pool.first_address().is_ipv4() {
            ip_pool.clone()
        } else {
            "198.18.0.0/15".parse::<IpCidr>().expect("valid IPv4 virtual DNS pool")
        };
        let ipv6_pool = if ip_pool.first_address().is_ipv6() {
            ip_pool
        } else {
            "fd77:ab0e:1::/112".parse::<IpCidr>().expect("valid IPv6 virtual DNS pool")
        };
        Self {
            trailing_dot: false,
            next_ipv4_addr: ipv4_pool.first_address(),
            ipv4_network_addr: ipv4_pool.first_address(),
            ipv4_broadcast_addr: ipv4_pool.last_address(),
            next_ipv6_addr: ipv6_pool.first_address(),
            ipv6_network_addr: ipv6_pool.first_address(),
            ipv6_broadcast_addr: ipv6_pool.last_address(),
            name_to_ip: HashMap::default(),
            lru_cache: LruCache::new_unbounded(),
        }
    }

    /// Only A and AAAA can be answered with a synthetic address. Other
    /// record types must be resolved by the upstream DNS server through proxy.
    pub fn is_synthetic_query(&self, data: &[u8]) -> Result<bool> {
        use crate::dns;
        let message = dns::parse_data_to_dns_message(data, false)?;
        Ok(
            message.queries.len() == 1
                && matches!(message.queries[0].query_type(), RecordType::A | RecordType::AAAA),
        )
    }

    /// Returns a synthetic DNS response for A/AAAA queries only.
    pub fn generate_query(&mut self, data: &[u8]) -> Result<(Vec<u8>, String, IpAddr)> {
        use crate::dns;
        let mut message = dns::parse_data_to_dns_message(data, false)?;
        let qname = dns::extract_domain_from_dns_message(&message)?;
        let query_type = message.queries[0].query_type();
        if !matches!(query_type, RecordType::A | RecordType::AAAA) {
            return Err("Virtual DNS only synthesizes A and AAAA records".into());
        }
        let ip = self.find_or_allocate_ip(qname.clone(), query_type == RecordType::AAAA)?;
        if matches!((query_type, ip.is_ipv4()), (RecordType::A, true) | (RecordType::AAAA, false)) {
            message = dns::build_dns_response(message, &qname, ip, 5)?;
        } else {
            message.metadata.message_type = MessageType::Response;
        }
        Ok((message.to_vec()?, qname, ip))
    }

    fn increment_ip(addr: IpAddr) -> Result<IpAddr> {
        let mut ip_bytes = match addr as IpAddr {
            IpAddr::V4(ip) => Vec::<u8>::from(ip.octets()),
            IpAddr::V6(ip) => Vec::<u8>::from(ip.octets()),
        };

        // Traverse bytes from right to left and stop when we can add one.
        for j in 0..ip_bytes.len() {
            let i = ip_bytes.len() - 1 - j;
            if ip_bytes[i] != 255 {
                // We can add 1 without carry and are done.
                ip_bytes[i] += 1;
                break;
            } else {
                // Zero this byte and carry over to the next one.
                ip_bytes[i] = 0;
            }
        }

        let addr = if addr.is_ipv4() {
            let bytes: [u8; 4] = ip_bytes.as_slice().try_into()?;
            IpAddr::V4(Ipv4Addr::from(bytes))
        } else {
            let bytes: [u8; 16] = ip_bytes.as_slice().try_into()?;
            IpAddr::V6(Ipv6Addr::from(bytes))
        };
        Ok(addr)
    }

    // This is to be called whenever we receive or send a packet on the socket
    // which connects the tun interface to the client, so existing IP address to name
    // mappings to not expire as long as the connection is active.
    pub fn touch_ip(&mut self, addr: &IpAddr) {
        _ = self.lru_cache.get_mut(addr).map(|entry| {
            entry.expiry = Instant::now() + Duration::from_secs(MAPPING_TIMEOUT);
        });
    }

    pub fn resolve_ip(&mut self, addr: &IpAddr) -> Option<&String> {
        self.lru_cache.get(addr).map(|entry| &entry.name)
    }

    fn find_or_allocate_ip(&mut self, name: String, ipv6: bool) -> Result<IpAddr> {
        // This function is a search and creation function.
        // Thus, it is sufficient to canonicalize the name here.
        let insert_name = if name.ends_with('.') && !self.trailing_dot {
            String::from(name.trim_end_matches('.'))
        } else {
            name
        };

        let now = Instant::now();

        // Iterate through all entries of the LRU cache and remove those that have expired.
        loop {
            // Copy the key and name out of the cache before mutating it.
            let expired = self.lru_cache.iter().next().and_then(|(ip, entry)| {
                (now > entry.expiry).then(|| (*ip, entry.name.clone()))
            });

            match expired {
                Some((ip, name)) => {
                    self.lru_cache.remove(&ip);
                    self.name_to_ip.remove(&(name, ip.is_ipv6()));
                }
                None => break, // The oldest entry is still valid; newer entries are too.
            }
        }

        // Return the IP if it is stored inside our LRU cache.
        let key = (insert_name.clone(), ipv6);
        if let Some(ip) = self.name_to_ip.get(&key) {
            let ip = *ip;
            self.touch_ip(&ip);
            return Ok(ip);
        }

        // Allocate independently per address family. A and AAAA lookups
        // for the same hostname must both resolve to the same hostname.
        let (mut next_addr, network_addr, broadcast_addr) = if ipv6 {
            (self.next_ipv6_addr, self.ipv6_network_addr, self.ipv6_broadcast_addr)
        } else {
            (self.next_ipv4_addr, self.ipv4_network_addr, self.ipv4_broadcast_addr)
        };
        let started_at = next_addr;

        loop {
            if let RawEntryMut::Vacant(vacant) = self.lru_cache.raw_entry_mut().from_key(&next_addr) {
                let expiry = Instant::now() + Duration::from_secs(MAPPING_TIMEOUT);
                vacant.insert(next_addr, NameCacheEntry { name: insert_name, expiry });
                self.name_to_ip.insert(key, next_addr);
                if ipv6 {
                    self.next_ipv6_addr = next_addr;
                } else {
                    self.next_ipv4_addr = next_addr;
                }
                return Ok(next_addr);
            }
            next_addr = Self::increment_ip(next_addr)?;
            if next_addr == broadcast_addr {
                next_addr = network_addr;
            }
            if next_addr == started_at {
                return Err("Virtual IP space for DNS exhausted".into());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use hickory_proto::{
        op::{Message, OpCode, Query},
        rr::{Name, RecordType},
    };
    use std::str::FromStr;

    fn query_wire(name: &str, query_type: RecordType) -> Vec<u8> {
        let mut message = Message::new(0, MessageType::Query, OpCode::Query);
        message.add_query(Query::query(Name::from_str(name).unwrap(), query_type));
        message.to_vec().unwrap()
    }

    fn dns(pool: &str) -> VirtualDns {
        VirtualDns::new(IpCidr::from_str(pool).unwrap())
    }

    #[test]
    fn only_a_and_aaaa_are_synthetic_dns_queries() {
        let dns = dns("198.18.0.0/15");
        assert!(dns.is_synthetic_query(&query_wire("example.org.", RecordType::A)).unwrap());
        assert!(dns.is_synthetic_query(&query_wire("example.org.", RecordType::AAAA)).unwrap());
        assert!(!dns.is_synthetic_query(&query_wire("example.org.", RecordType::TXT)).unwrap());
        assert!(!dns.is_synthetic_query(&query_wire("_service._tcp.example.org.", RecordType::SRV)).unwrap());
    }

    #[test]
    fn virtual_dns_rejects_non_address_query_synthesis() {
        let mut dns = dns("198.18.0.0/15");
        assert!(dns.generate_query(&query_wire("example.org.", RecordType::TXT)).is_err());
    }

    #[test]
    fn virtual_dns_answers_both_address_families_independent_of_configured_pool() {
        let mut ipv4_dns = dns("198.18.0.0/15");
        let (a_response, _, ipv4) = ipv4_dns.generate_query(&query_wire("example.org.", RecordType::A)).unwrap();
        assert!(ipv4.is_ipv4());
        let a_response = Message::from_vec(&a_response).unwrap();
        assert_eq!(a_response.answers.len(), 1);
        assert!(matches!(a_response.answers[0].data, hickory_proto::rr::RData::A(_)));

        let (aaaa_response, _, ipv6) = ipv4_dns.generate_query(&query_wire("example.org.", RecordType::AAAA)).unwrap();
        assert!(ipv6.is_ipv6());
        let aaaa_response = Message::from_vec(&aaaa_response).unwrap();
        assert_eq!(aaaa_response.answers.len(), 1);
        assert!(matches!(aaaa_response.answers[0].data, hickory_proto::rr::RData::AAAA(_)));

        let mut ipv6_dns = dns("2001:db8::/120");
        let (aaaa_response, _, ipv6) = ipv6_dns.generate_query(&query_wire("example.org.", RecordType::AAAA)).unwrap();
        assert!(ipv6.is_ipv6());
        let aaaa_response = Message::from_vec(&aaaa_response).unwrap();
        assert_eq!(aaaa_response.answers.len(), 1);
        assert!(matches!(aaaa_response.answers[0].data, hickory_proto::rr::RData::AAAA(_)));

        let (a_response, _, ipv4) = ipv6_dns.generate_query(&query_wire("example.org.", RecordType::A)).unwrap();
        assert!(ipv4.is_ipv4());
        let a_response = Message::from_vec(&a_response).unwrap();
        assert_eq!(a_response.answers.len(), 1);
        assert!(matches!(a_response.answers[0].data, hickory_proto::rr::RData::A(_)));
    }

    
    #[test]
    fn ipv4_pool_also_allocates_distinct_virtual_ipv6_for_aaaa() {
        let mut dns = dns("198.18.0.0/15");
        let (a_response, name_a, ipv4) =
            dns.generate_query(&query_wire("dual.example.", RecordType::A)).unwrap();
        let (aaaa_response, name_aaaa, ipv6) =
            dns.generate_query(&query_wire("dual.example.", RecordType::AAAA)).unwrap();

        assert_eq!(name_a, name_aaaa);
        assert!(ipv4.is_ipv4());
        assert!(ipv6.is_ipv6());
        assert_ne!(ipv4, ipv6);
        assert_eq!(dns.resolve_ip(&ipv4).map(String::as_str), Some("dual.example"));
        assert_eq!(dns.resolve_ip(&ipv6).map(String::as_str), Some("dual.example"));

        let a_response = Message::from_vec(&a_response).unwrap();
        let aaaa_response = Message::from_vec(&aaaa_response).unwrap();
        assert!(matches!(a_response.answers[0].data, hickory_proto::rr::RData::A(_)));
        assert!(matches!(aaaa_response.answers[0].data, hickory_proto::rr::RData::AAAA(_)));
    }

    #[test]
    fn expired_mapping_is_reclaimed_and_active_mapping_can_be_touched() {
        let mut dns = dns("198.18.0.0/29");
        let name = "expired.example".to_string();
        let ip = dns.find_or_allocate_ip(name.clone(), false).unwrap();
        dns.lru_cache.get_mut(&ip).unwrap().expiry = Instant::now() - Duration::from_secs(1);

        let reclaimed_ip = dns.find_or_allocate_ip(name.clone(), false).unwrap();
        assert_eq!(reclaimed_ip, ip);

        dns.lru_cache.get_mut(&ip).unwrap().expiry = Instant::now() - Duration::from_secs(1);
        dns.touch_ip(&ip);
        let touched_ip = dns.find_or_allocate_ip(name, false).unwrap();
        assert_eq!(touched_ip, ip);
    }
}
