CREATE TABLE host_network_inventory (
 host_id UUID PRIMARY KEY REFERENCES host(id) ON DELETE CASCADE,
 interfaces JSONB NOT NULL DEFAULT '[]',
 routes JSONB NOT NULL DEFAULT '[]',
 observed_at TIMESTAMPTZ NOT NULL
);
