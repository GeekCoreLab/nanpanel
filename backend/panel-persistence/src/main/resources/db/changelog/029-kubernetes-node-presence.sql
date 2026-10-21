ALTER TABLE kubernetes_cluster_node
    ADD COLUMN node_present BOOLEAN NOT NULL DEFAULT TRUE;
