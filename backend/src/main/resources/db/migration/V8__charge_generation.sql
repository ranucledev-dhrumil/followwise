INSERT INTO users (
    id,
    email,
    password_hash,
    failed_attempts,
    locked_until,
    created_at,
    updated_at,
    email_verified_at
) VALUES (
    '00000000-0000-0000-0000-000000000001',
    'system@followwise.invalid',
    '{bcrypt}$2a$10$AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
    0,
    'infinity',
    NOW(),
    NOW(),
    NULL
) ON CONFLICT (id) DO NOTHING;
