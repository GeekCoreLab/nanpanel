CREATE TABLE host_container_deployment (
 host_id UUID NOT NULL REFERENCES host(id) ON DELETE CASCADE,
 container_name VARCHAR(63) NOT NULL,
 image VARCHAR(255) NOT NULL,
 host_port INTEGER CHECK (host_port BETWEEN 1 AND 65535),
 container_port INTEGER CHECK (container_port BETWEEN 1 AND 65535),
 created_at BIGINT NOT NULL,
 PRIMARY KEY (host_id, container_name)
);
