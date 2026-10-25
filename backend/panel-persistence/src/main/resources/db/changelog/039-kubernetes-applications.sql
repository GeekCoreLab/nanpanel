CREATE TABLE kubernetes_application (
 id UUID PRIMARY KEY,
 cluster_id UUID NOT NULL REFERENCES kubernetes_cluster(id) ON DELETE CASCADE,
 namespace VARCHAR(63) NOT NULL,
 application_name VARCHAR(63) NOT NULL,
 display_name VARCHAR(80) NOT NULL,
 template_id VARCHAR(64) NOT NULL,
 current_version INTEGER NOT NULL DEFAULT 0 CHECK (current_version >= 0),
 state VARCHAR(16) NOT NULL CHECK (state IN ('APPLYING','DEPLOYED','FAILED','PARTIAL')),
 created_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE (cluster_id, namespace, application_name)
);

CREATE INDEX kubernetes_application_cluster_namespace
 ON kubernetes_application(cluster_id, namespace, created_at DESC);

CREATE TABLE kubernetes_application_version (
 application_id UUID NOT NULL REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 version INTEGER NOT NULL CHECK (version > 0),
 base_version INTEGER NOT NULL CHECK (base_version >= 0),
 manifest_yaml TEXT NOT NULL,
 manifest_sha256 CHAR(64) NOT NULL,
 manifest_diff TEXT NOT NULL DEFAULT '',
 state VARCHAR(16) NOT NULL CHECK (state IN ('DRAFT','APPLYING','DEPLOYED','FAILED','PARTIAL')),
 error_message VARCHAR(1000),
 requested_by VARCHAR(36) NOT NULL REFERENCES panel_user(id),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 deployed_at TIMESTAMPTZ,
 PRIMARY KEY (application_id, version)
);

CREATE INDEX kubernetes_application_version_history
 ON kubernetes_application_version(application_id, version DESC);

CREATE TABLE kubernetes_application_resource (
 application_id UUID NOT NULL REFERENCES kubernetes_application(id) ON DELETE CASCADE,
 resource_kind VARCHAR(32) NOT NULL CHECK (resource_kind IN ('Deployment','Service','ServiceAccount')),
 resource_name VARCHAR(253) NOT NULL,
 resource_uid VARCHAR(128) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 PRIMARY KEY (application_id, resource_kind, resource_name),
 UNIQUE (application_id, resource_uid)
);
