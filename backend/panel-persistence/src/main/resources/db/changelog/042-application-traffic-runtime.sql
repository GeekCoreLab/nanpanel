CREATE TABLE kubernetes_application_traffic_runtime (
 application_id UUID PRIMARY KEY REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 revision_id UUID NOT NULL,
 generation BIGINT NOT NULL CHECK (generation > 0),
 state VARCHAR(20) NOT NULL CHECK (state IN ('APPLYING','APPLIED','VERIFY_FAILED','COMPENSATING','COMPENSATED','FAILED','PARTIAL')),
 runtime_snapshot TEXT NOT NULL,
 detail TEXT,
 updated_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at BIGINT NOT NULL,
 updated_at BIGINT NOT NULL,
 applied_at BIGINT
);

CREATE INDEX kubernetes_application_traffic_runtime_state
 ON kubernetes_application_traffic_runtime(state, updated_at DESC);

CREATE TABLE kubernetes_application_traffic_runtime_resource (
 application_id UUID NOT NULL REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 resource_name VARCHAR(63) NOT NULL,
 resource_uid VARCHAR(128) NOT NULL,
 PRIMARY KEY (application_id,resource_name),
 UNIQUE (application_id,resource_uid)
);
