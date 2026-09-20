CREATE TABLE IF NOT EXISTS users (
    user_id VARCHAR(50) PRIMARY KEY,
    username VARCHAR(50) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    salt VARCHAR(50),
    email VARCHAR(100)
);

DO
$do$
BEGIN
   IF NOT EXISTS (
      SELECT FROM pg_catalog.pg_roles WHERE rolname = 'valoq_auth_service'
   ) THEN
      CREATE ROLE valoq_auth_service WITH
          LOGIN
          PASSWORD 'auth_service_password'
          NOSUPERUSER
          NOCREATEDB
          NOCREATEROLE;
   END IF;
END
$do$;

REVOKE ALL ON SCHEMA public FROM valoq_auth_service;
GRANT USAGE ON SCHEMA public TO valoq_auth_service;

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE users TO valoq_auth_service;
