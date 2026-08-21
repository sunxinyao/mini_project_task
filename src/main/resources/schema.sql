-- 任务表：状态 QUEUED / RUNNING / SUCCEEDED / FAILED
CREATE TABLE IF NOT EXISTS `task` (
    `id`               BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `task_id`         VARCHAR(64)   NOT NULL,
    `type`             VARCHAR(128)  NOT NULL,
    `payload`          TEXT          NOT NULL,
    `status`           VARCHAR(16)   NOT NULL,
    `attempt_count`    INT           NOT NULL DEFAULT 0,
    `max_attempts`     INT           NOT NULL DEFAULT 3,
    `claimed_by`       VARCHAR(64)   NULL,
    `claim_token`      VARCHAR(64)   NULL,
    `lease_expires_at` DATETIME(3)   NULL,
    `last_error`       TEXT          NULL,
    `result`           TEXT          NULL,
    `idempotency_key`  VARCHAR(128)  NOT NULL,
    `request_hash`     VARCHAR(64)   NOT NULL,
    `created_at`       DATETIME(3)   NOT NULL,
    `updated_at`       DATETIME(3)   NOT NULL,
    UNIQUE KEY `uk_task_task_id` (`task_id`),
    UNIQUE KEY `uk_task_idempotency_key` (`idempotency_key`),
    KEY `idx_task_poll` (`status`, `id`)
);

-- 工作人员表：首次领取任务时自动注册
CREATE TABLE IF NOT EXISTS `worker` (
    `id`             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    `worker_id`      VARCHAR(64)  NOT NULL,
    `created_at`     DATETIME(3)  NOT NULL,
    `last_seen_at`   DATETIME(3)  NOT NULL,
    `last_claim_at`  DATETIME(3)  NULL,
    `claim_count`    INT          NOT NULL DEFAULT 0,
    UNIQUE KEY `uk_worker_worker_id` (`worker_id`)
);
