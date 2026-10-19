CREATE TABLE agent_gateway_fencing (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  epoch BIGINT NOT NULL CHECK (epoch >= 0)
);

INSERT INTO agent_gateway_fencing (id, epoch) VALUES (1, 0);
