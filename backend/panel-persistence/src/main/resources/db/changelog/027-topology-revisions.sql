CREATE TABLE topology_revision (
 id VARCHAR(36) PRIMARY KEY,
 generation BIGINT NOT NULL UNIQUE CHECK (generation >= 0),
 snapshot TEXT NOT NULL,
 updated_by VARCHAR(36),
 updated_at BIGINT NOT NULL
);
CREATE TABLE topology_head (
 id INTEGER PRIMARY KEY CHECK (id = 1),
 revision_id VARCHAR(36) NOT NULL,
 generation BIGINT NOT NULL CHECK (generation >= 0),
 snapshot TEXT NOT NULL,
 updated_by VARCHAR(36),
 updated_at BIGINT NOT NULL
);
INSERT INTO topology_revision (id, generation, snapshot, updated_by, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 0, '{"nodes":[],"links":[]}', NULL, 0);
INSERT INTO topology_head (id, revision_id, generation, snapshot, updated_by, updated_at)
VALUES (1, '00000000-0000-0000-0000-000000000001', 0, '{"nodes":[],"links":[]}', NULL, 0);
CREATE INDEX topology_revision_generation_idx ON topology_revision(generation DESC);
