-- ============================================================
-- Bang dich (archive) - cau truc giong bang goc + cot archived_at
-- Chay 1 lan de tao bang truoc khi chay job
-- ============================================================

CREATE TABLE IF NOT EXISTS user_banners_archive (
    id               BIGINT PRIMARY KEY,          -- giu nguyen id goc, khong sinh moi
    contract_number  VARCHAR(50)  NOT NULL,
    identity         VARCHAR(20)  NOT NULL,
    phone            VARCHAR(20)  NOT NULL,
    key_banner       VARCHAR(100) NOT NULL,
    start_time       TIMESTAMPTZ  NOT NULL,
    end_time         TIMESTAMPTZ  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    archived_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

-- Index phuc vu truy van bao cao tren bang archive (tuy nhu cau)
CREATE INDEX IF NOT EXISTS idx_user_banners_archive_created_at
    ON user_banners_archive (created_at);

CREATE INDEX IF NOT EXISTS idx_user_banners_archive_contract_number
    ON user_banners_archive (contract_number);

-- ------------------------------------------------------------
-- NANG CAO (tuy chon): neu bang archive se rat lon theo thoi gian,
-- nen PARTITION theo thang tu dau de query/xoa du lieu cu de dang hon:
--
-- CREATE TABLE user_banners_archive (
--     ... cac cot nhu tren ...
-- ) PARTITION BY RANGE (created_at);
--
-- CREATE TABLE user_banners_archive_2026_07
--     PARTITION OF user_banners_archive
--     FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
-- ------------------------------------------------------------
