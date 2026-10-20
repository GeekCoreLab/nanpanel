CREATE TABLE kubernetes_service_inventory (
 source_id VARCHAR(80) PRIMARY KEY,
 namespace VARCHAR(63) NOT NULL,
 observed_at TIMESTAMPTZ NOT NULL,
 inventory_json TEXT NOT NULL
);
