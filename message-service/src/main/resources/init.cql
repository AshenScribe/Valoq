CREATE KEYSPACE IF NOT EXISTS valoq_messages
WITH replication = {
    'class': 'SimpleStrategy',
    'replication_factor': 1
};


-- 1. CONVERSATION MEMBERS
-- Current authorization/membership state.
-- Used for:
--   - checking whether a user can send/read in a conversation
--   - determining current members
--   - determining who receives/fans out new events
-- The event log separately records membership changes.
CREATE TABLE IF NOT EXISTS valoq_messages.conversation_members (
    conversation_id uuid,
    user_id uuid,
    role text,                  -- member | admin | owner
    joined_at timestamp,
    PRIMARY KEY ((conversation_id), user_id)
);

-- 2. EVENTS
-- Immutable conversation event history.
-- Examples:
--   MESSAGE_CREATED
--   MESSAGE_EDITED
--   MESSAGE_DELETED
--   REACTION_ADDED
--   REACTION_REMOVED
--   MEMBER_ADDED
--   MEMBER_REMOVED
--   CONVERSATION_CREATED
--   CONVERSATION_UPDATED
-- DM:
--   hash_bucket = 0
-- Group:
--   hash_bucket = deterministic bucket for the event
-- time_bucket:
--   YYYY-MM
-- event_id is the ordering/cursor value.
CREATE TABLE IF NOT EXISTS valoq_messages.events (
    conversation_id uuid,
    time_bucket text,           -- YYYY-MM
    hash_bucket int,             -- 0..N-1
    event_id timeuuid,
    event_type text,
    actor_id uuid,
    entity_id uuid,
    payload text,
    PRIMARY KEY (
        (conversation_id, time_bucket, hash_bucket),
        event_id
    )
) WITH CLUSTERING ORDER BY (event_id ASC);

-- 3. USER EVENTS
-- Per-user synchronization index.
-- This does NOT duplicate the event payload.
-- It tells the server:
--   "these are the events this user needs to synchronize."
-- The actual event is retrieved from events.
-- Used for:
--   INIT + last_sync_event
--   reconnect
--   global cross-conversation synchronization
CREATE TABLE IF NOT EXISTS valoq_messages.user_events (
    user_id uuid,
    time_bucket text,            -- YYYY-MM
    event_id timeuuid,
    conversation_id uuid,
    PRIMARY KEY ((user_id, time_bucket), event_id)
) WITH CLUSTERING ORDER BY (event_id ASC);

CREATE ROLE IF NOT EXISTS valoq_message_service
WITH PASSWORD = 'message_service_password'
AND LOGIN = true
AND SUPERUSER = false;

GRANT SELECT, MODIFY
ON TABLE valoq_messages.conversation_members
TO valoq_message_service;

GRANT SELECT, MODIFY
ON TABLE valoq_messages.events
TO valoq_message_service;

GRANT SELECT, MODIFY
ON TABLE valoq_messages.user_events
TO valoq_message_service;
