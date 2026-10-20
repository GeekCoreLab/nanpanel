CREATE TABLE host_overlay_peer_snapshot (
 host_id UUID PRIMARY KEY REFERENCES host(id) ON DELETE CASCADE,
 observed_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE host_overlay_peer_inventory (
 host_id UUID NOT NULL REFERENCES host_overlay_peer_snapshot(host_id) ON DELETE CASCADE,
 peer_name VARCHAR(253) NOT NULL,
 connection_status VARCHAR(16) NOT NULL
  CHECK (connection_status IN ('CONNECTED','DISCONNECTED','CONNECTING','UNKNOWN')),
 connection_type VARCHAR(16) NOT NULL
  CHECK (connection_type IN ('P2P','RELAY','UNKNOWN')),
 PRIMARY KEY (host_id,peer_name),
 CHECK (connection_status = 'CONNECTED' OR connection_type = 'UNKNOWN')
);
