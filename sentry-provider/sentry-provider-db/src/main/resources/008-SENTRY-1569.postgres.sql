-- Table `SENTRY_PERM_CHANGE` for classes [org.apache.sentry.provider.db.service.model.MSentryPermChange]
CREATE TABLE "SENTRY_PERM_CHANGE"
(
    "CHANGE_ID" bigint NOT NULL,
    "CREATE_TIME_MS" bigint NOT NULL,
    "PERM_CHANGE" VARCHAR(4000) NOT NULL,
    CONSTRAINT "SENTRY_PERM_CHANGE_PK" PRIMARY KEY ("CHANGE_ID")
);

-- Table `SENTRY_PATH_CHANGE` for classes [org.apache.sentry.provider.db.service.model.MSentryPathChange]
CREATE TABLE "SENTRY_PATH_CHANGE"
(
    "CHANGE_ID" bigint NOT NULL,
    "NOTIFICATION_ID" bigint NOT NULL,
    "CREATE_TIME_MS" bigint NOT NULL,
    "PATH_CHANGE" VARCHAR(4000) NOT NULL,
    CONSTRAINT "SENTRY_PATH_CHANGE_PK" PRIMARY KEY ("CHANGE_ID")
);

-- Constraints for table SENTRY_PATH_CHANGE for class [org.apache.sentry.provider.db.service.model.MSentryPathChange]
CREATE UNIQUE INDEX "NOTIFICATIONID" ON "SENTRY_PATH_CHANGE" ("NOTIFICATION_ID");