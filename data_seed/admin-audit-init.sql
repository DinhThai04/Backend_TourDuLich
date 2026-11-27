USE sgu_reporting;
SOURCE /docker-entrypoint-initdb.d/sql/V013__admin_audit_metadata.sql;
USE sgu_pricing;
SOURCE /docker-entrypoint-initdb.d/sql/V014__pricing_audit_outbox.sql;
