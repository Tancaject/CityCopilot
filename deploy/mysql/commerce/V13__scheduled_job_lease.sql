CREATE TABLE commerce_job_lease (
    job_name VARCHAR(64) NOT NULL PRIMARY KEY,
    owner_id VARCHAR(128) NULL,
    fencing_token BIGINT NOT NULL DEFAULT 0,
    lease_until TIMESTAMP(3) NULL
) ENGINE=InnoDB;
