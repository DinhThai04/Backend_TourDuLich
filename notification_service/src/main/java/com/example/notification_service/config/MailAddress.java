package com.example.notification_service.config;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

public final class MailAddress {

    private MailAddress() {
    }

    public static void validate(String value) {
        try {
            if (value == null || value.length() > 254 || value.contains("\r") || value.contains("\n"))
                throw new AddressException("Invalid email");
            InternetAddress address = new InternetAddress(value, true);
            address.validate();
            if (!value.equals(address.getAddress()) || address.getPersonal() != null)
                throw new AddressException("Expected a single plain email address");
        } catch (AddressException exception) {
            throw new IllegalArgumentException("Invalid recipient or sender email");
        }
    }
}
