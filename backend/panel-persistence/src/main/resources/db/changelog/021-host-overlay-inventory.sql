CREATE TABLE host_overlay_inventory (
 host_id UUID PRIMARY KEY REFERENCES host(id) ON DELETE CASCADE,
 peer_count INTEGER NOT NULL,
 connected_peer_count INTEGER NOT NULL,
 p2p_peer_count INTEGER NOT NULL,
 relayed_peer_count INTEGER NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL,
 CHECK (peer_count BETWEEN 0 AND 100000),
 CHECK (connected_peer_count BETWEEN 0 AND peer_count),
 CHECK (p2p_peer_count BETWEEN 0 AND connected_peer_count),
 CHECK (relayed_peer_count BETWEEN 0 AND connected_peer_count),
 CHECK (p2p_peer_count + relayed_peer_count = connected_peer_count)
);
