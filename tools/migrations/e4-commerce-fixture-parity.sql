-- Correct the checked-in demo fixture to byte-for-byte parity with the legacy
-- coupon row. UNHEX avoids client and console escaping differences for the
-- literal backslash-n sequences retained by the source fixture.
UPDATE commerce_voucher
SET rules=CONVERT(UNHEX('E585A8E59CBAE9809AE794A85C6EE697A0E99C80E9A284E7BAA65C6EE58FAFE697A0E99990E58FA0E58AA05CE4B88DE58591E78EB0E38081E4B88DE689BEE99BB65C6EE4BB85E99990E5A082E9A39F') USING utf8mb4)
WHERE id=1;
