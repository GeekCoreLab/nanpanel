CREATE TABLE kubernetes_application_network_topology_head (
 application_id UUID PRIMARY KEY REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 revision_id UUID,
 generation BIGINT NOT NULL DEFAULT 0 CHECK (generation >= 0),
 snapshot TEXT NOT NULL,
 updated_by VARCHAR(36) REFERENCES panel_user(id),
 updated_at BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE kubernetes_application_network_topology_revision (
 application_id UUID NOT NULL REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 id UUID NOT NULL,
 generation BIGINT NOT NULL CHECK (generation > 0),
 snapshot TEXT NOT NULL,
 updated_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 updated_at BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY (application_id, id),
 UNIQUE (application_id, generation)
);

CREATE INDEX kubernetes_application_network_topology_history
 ON kubernetes_application_network_topology_revision(application_id, generation DESC);
