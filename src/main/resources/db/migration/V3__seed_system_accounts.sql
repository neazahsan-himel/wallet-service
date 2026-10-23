-- Fixed, well-known ids so code and finance queries can refer to them directly.
-- Biller accounts are added with the bill payment feature.
INSERT INTO accounts (id, owner_id, type, created_at) VALUES
    ('00000000-0000-7000-8000-000000000001', NULL, 'FEE',     now()),
    ('00000000-0000-7000-8000-000000000002', NULL, 'CASH_IN', now());
