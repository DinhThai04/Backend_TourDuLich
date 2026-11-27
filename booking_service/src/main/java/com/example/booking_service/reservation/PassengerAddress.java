package com.example.booking_service.reservation;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class PassengerAddress {
    private static final Pattern STREET_CONTENT = Pattern.compile("[\\p{L}\\p{N}]");
    private static final Map<String, Set<String>> WARDS_BY_PROVINCE = loadAddresses();

    private PassengerAddress() {}

    static boolean isValid(String address) {
        if (address == null) return false;
        int provinceSeparator = address.lastIndexOf(',');
        if (provinceSeparator < 0) return false;
        int wardSeparator = address.lastIndexOf(',', provinceSeparator - 1);
        if (wardSeparator < 0 || !STREET_CONTENT.matcher(address.substring(0, wardSeparator)).find()) return false;
        Set<String> wards = WARDS_BY_PROVINCE.get(address.substring(provinceSeparator + 1).strip());
        return wards != null && wards.contains(address.substring(wardSeparator + 1, provinceSeparator).strip());
    }

    private static Map<String, Set<String>> loadAddresses() {
        // Keep the catalog in sync with the checkout's Vietnam administrative address dataset.
        try (var input = PassengerAddress.class.getResourceAsStream("/address/vietnamAddress.json")) {
            if (input == null) throw new IllegalStateException("Passenger address catalog is missing");
            Map<String, Set<String>> result = new HashMap<>();
            for (var province : new ObjectMapper().readTree(input)) {
                Set<String> wards = new HashSet<>();
                for (var ward : province.get(2)) wards.add(ward.get(1).asText());
                result.put(province.get(1).asText(), Set.copyOf(wards));
            }
            return Map.copyOf(result);
        } catch (IOException invalid) {
            throw new IllegalStateException("Cannot load passenger address catalog", invalid);
        }
    }
}
