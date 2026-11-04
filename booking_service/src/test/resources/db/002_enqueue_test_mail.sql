-- TEST FIXTURE ONLY. Loaded by automated tests. Never import into dbtour_v2.
-- OPTIONAL: enqueue one test mail only to the authorised user's mailbox.
-- Import 001 first. Does not create a booking/payment or modify seats.
-- MAIL_ENABLED must be true before worker can deliver this item.
-- Re-importing this file will NOT resend the same SENT test event.
INSERT INTO mail_outbox
 (event_key,event_type,source_service,booking_id,account_id,recipient,payload,
  scheduled_at,status,next_attempt_at,created_at,updated_at)
VALUES
 ('test:phase-b:manual:1','TEST_MAIL','booking_service',NULL,NULL,
  'tester@example.com',
  '{"version":1,"customerName":"Danh","tourTitle":"Kiểm tra hệ thống email","passengerCount":0}',
  NOW(6),'PENDING',NOW(6),NOW(6),NOW(6))
ON DUPLICATE KEY UPDATE id=id;

SELECT id,event_key,status,attempt_count,sent_at,last_error
FROM mail_outbox WHERE event_key='test:phase-b:manual:1';
