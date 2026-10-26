CREATE TABLE kubernetes_application_network_runtime (
 application_id UUID PRIMARY KEY REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 revision_id UUID NOT NULL,
 generation BIGINT NOT NULL CHECK (generation > 0),
 cluster_node_uid VARCHAR(128) NOT NULL,
 host_id UUID NOT NULL REFERENCES host(id),
 state VARCHAR(20) NOT NULL CHECK (state IN ('APPLYING','APPLIED','VERIFYING','VERIFY_FAILED','COMPENSATING','COMPENSATED','FAILED','PARTIAL')),
 helper_operation_id UUID REFERENCES operation(id),
 runtime_snapshot TEXT NOT NULL,
 detail TEXT,
 updated_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 applied_at BIGINT,
 CHECK (length(cluster_node_uid) BETWEEN 1 AND 128)
);

CREATE INDEX kubernetes_application_network_runtime_state
 ON kubernetes_application_network_runtime(state, updated_at DESC);

CREATE TABLE kubernetes_application_network_runtime_cidr (
 application_id UUID NOT NULL REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 attachment_id UUID NOT NULL,
 cidr VARCHAR(18) NOT NULL UNIQUE,
 PRIMARY KEY (application_id, attachment_id)
);

CREATE TABLE kubernetes_application_network_cidr_allocator (
 id SMALLINT PRIMARY KEY CHECK (id = 1),
 next_index INTEGER NOT NULL CHECK (next_index BETWEEN 0 AND 511)
);
INSERT INTO kubernetes_application_network_cidr_allocator (id,next_index) VALUES (1,0);

CREATE TABLE kubernetes_application_network_runtime_resource (
 application_id UUID NOT NULL REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 resource_kind VARCHAR(40) NOT NULL CHECK (resource_kind IN ('NetworkAttachmentDefinition','EndpointSlice')),
 resource_name VARCHAR(253) NOT NULL,
 resource_uid VARCHAR(128) NOT NULL,
 PRIMARY KEY (application_id,resource_kind,resource_name),
 UNIQUE (application_id,resource_uid)
);
