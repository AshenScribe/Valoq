CREATE KEYSPACE IF NOT EXISTS valoq_messages
WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};

CREATE TABLE IF NOT EXISTS valoq_messages.messages (
    conversation_id text,
    message_id timeuuid,
    sender_id text,
    recipient_id text,
    payload text,
    created_at timestamp,
    PRIMARY KEY ((conversation_id), message_id)
) WITH CLUSTERING ORDER BY (message_id ASC);