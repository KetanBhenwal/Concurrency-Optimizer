INSERT INTO reservation_seats (reservation_id, seat_id)
SELECT reservation_id, id
FROM seats
WHERE reservation_id IS NOT NULL
ON CONFLICT DO NOTHING;