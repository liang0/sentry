SELECT 'Upgrading Sentry store schema from 2.0.0 to 2.1.0';
\i 010-SENTRY-2210.postgres.sql;
\i 011-SENTRY-2154.postgres.sql;

UPDATE "SENTRY_VERSION" SET "SCHEMA_VERSION"='2.1.0', "VERSION_COMMENT"='Sentry release version 2.1.0' WHERE "VER_ID"=1;

SELECT 'Finished upgrading Sentry store schema from 2.0.0 to 2.1.0';