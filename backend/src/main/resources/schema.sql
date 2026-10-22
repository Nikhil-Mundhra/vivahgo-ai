-- PostgreSQL Schema for VivahGo-AI ACID Ledger
-- Covers Issue #1 and Issue #3 requirements

CREATE TABLE IF NOT EXISTS vendor_reservations (
    id UUID NOT NULL PRIMARY KEY,
    vendor_id VARCHAR(100) NOT NULL,
    vendor_name VARCHAR(255) NOT NULL,
    vendor_category VARCHAR(100) NOT NULL,
    wedding_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    event_date DATE NOT NULL,
    status VARCHAR(50) NOT NULL, -- 'HOLD_ACTIVE', 'CONFIRMED', 'RELEASED', 'EXPIRED'
    hold_expires_at TIMESTAMP,
    deposit_amount NUMERIC(12, 2) DEFAULT 0.00,
    currency VARCHAR(10) DEFAULT 'INR',
    metadata TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_reservations_vendor_date ON vendor_reservations(vendor_id, event_date);
CREATE INDEX IF NOT EXISTS idx_reservations_wedding ON vendor_reservations(wedding_id);
CREATE INDEX IF NOT EXISTS idx_reservations_status ON vendor_reservations(status);

CREATE TABLE IF NOT EXISTS operational_logs (
    id UUID NOT NULL PRIMARY KEY,
    agent_id VARCHAR(100) NOT NULL,
    wedding_id VARCHAR(100),
    action VARCHAR(100) NOT NULL,
    target_resource VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL, -- 'SUCCESS', 'LOCK_CONFLICT', 'FAILED'
    details TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_op_logs_wedding ON operational_logs(wedding_id);
CREATE INDEX IF NOT EXISTS idx_op_logs_action ON operational_logs(action);
