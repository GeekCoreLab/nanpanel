CREATE TABLE agent_relay_status (
  relay_id TEXT PRIMARY KEY,
  cluster_id TEXT NOT NULL,
  target_name TEXT NOT NULL,
  epoch BIGINT NOT NULL,
  healthy BOOLEAN NOT NULL,
  healthy_count INTEGER NOT NULL DEFAULT 0 CHECK (healthy_count >= 0),
  last_seen TIMESTAMPTZ NOT NULL,
  updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE dns_writer_lease (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  owner_id TEXT,
  epoch BIGINT NOT NULL DEFAULT 0 CHECK (epoch >= 0),
  lease_until TIMESTAMPTZ
);
INSERT INTO dns_writer_lease (id, epoch) VALUES (1, 0);

CREATE TABLE dns_managed_record (
  record_name TEXT PRIMARY KEY,
  host_id UUID REFERENCES host(id),
  record_id TEXT NOT NULL,
  content TEXT NOT NULL,
  ttl INTEGER NOT NULL,
  proxied BOOLEAN NOT NULL,
  state TEXT NOT NULL,
  detail TEXT,
  last_verified_at TIMESTAMPTZ,
  updated_at TIMESTAMPTZ NOT NULL
);
