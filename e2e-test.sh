#!/bin/bash
# E2E Test Script: Login -> Search -> Booking -> Payment -> Invoice
# Run this after: docker compose up -d
# Make executable: chmod +x e2e-test.sh

set -e

GATEWAY_URL="http://localhost:8888/api/v1"
FRONTEND_URL="http://localhost"

echo "=== SGU Tour E2E Test ==="
echo "Gateway: $GATEWAY_URL"
echo "Frontend: $FRONTEND_URL"
echo ""

# Step 1: Login
echo "Step 1: Login"
LOGIN_RESPONSE=$(curl -s -X POST "$GATEWAY_URL/auth/users/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"test@example.com","password":"password123"}')

echo "Login Response: $LOGIN_RESPONSE"

# Extract token (requires jq)
# TOKEN=$(echo $LOGIN_RESPONSE | jq -r '.result.token')
# REFRESH_TOKEN=$(echo $LOGIN_RESPONSE | jq -r '.result.refreshToken')
# USER_ID=$(echo $LOGIN_RESPONSE | jq -r '.result.user.id')

# For demo, using placeholder
TOKEN="YOUR_JWT_TOKEN_HERE"
USER_ID="1"

echo "Token: $TOKEN"
echo "User ID: $USER_ID"
echo ""

# Step 2: Search Tours
echo "Step 2: Search Tours"
SEARCH_RESPONSE=$(curl -s -X GET "$GATEWAY_URL/tour/tours/search?keyword=ha&page=0&size=10" \
  -H "Authorization: Bearer $TOKEN")

echo "Search Response: $SEARCH_RESPONSE"
echo ""

# Step 3: Get Tour Departures (assuming tour ID 1)
echo "Step 3: Get Tour Departures"
DEPARTURES_RESPONSE=$(curl -s -X GET "$GATEWAY_URL/tour/tours/1/departures" \
  -H "Authorization: Bearer $TOKEN")

echo "Departures Response: $DEPARTURES_RESPONSE"
echo ""

# Step 4: Create Booking
echo "Step 4: Create Booking"
BOOKING_REQUEST='{
  "userId": "'$USER_ID'",
  "tourDepartureId": "1",
  "listOfCustomers": [
    {
      "fullName": "Test User",
      "birthdate": "1990-01-01",
      "gender": "Male",
      "address": "Hanoi"
    }
  ]
}'

BOOKING_RESPONSE=$(curl -s -X POST "$GATEWAY_URL/booking" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$BOOKING_REQUEST")

echo "Booking Response: $BOOKING_RESPONSE"

# Extract booking ID
# BOOKING_ID=$(echo $BOOKING_RESPONSE | jq -r '.result.id')
BOOKING_ID="1"
echo "Booking ID: $BOOKING_ID"
echo ""

# Step 5: Get Payment Quote
echo "Step 5: Get Payment Quote"
QUOTE_RESPONSE=$(curl -s -X GET "$GATEWAY_URL/payment/quote/$BOOKING_ID" \
  -H "Authorization: Bearer $TOKEN")

echo "Quote Response: $QUOTE_RESPONSE"
echo ""

# Step 6: Create VNPAY Payment
echo "Step 6: Create VNPAY Payment"
VNPAY_REQUEST='{"bookingId": '$BOOKING_ID', "expectedAmount": "3350000"}'

VNPAY_RESPONSE=$(curl -s -X POST "$GATEWAY_URL/payment/vnpay" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "$VNPAY_REQUEST")

echo "VNPAY Response: $VNPAY_RESPONSE"
echo ""

# Step 7: Simulate Payment Callback (manual step - open URL in browser)
echo "Step 7: Open VNPAY Payment URL in browser"
echo "Payment URL: (from VNPAY response above)"
echo ""

# Step 8: Verify Booking Confirmed
echo "Step 8: Verify Booking Confirmed"
BOOKING_DETAIL=$(curl -s -X GET "$GATEWAY_URL/booking/$BOOKING_ID" \
  -H "Authorization: Bearer $TOKEN")

echo "Booking Detail: $BOOKING_DETAIL"
echo ""

# Step 9: Get Invoice
echo "Step 9: Get Invoice"
INVOICE_RESPONSE=$(curl -s -X GET "$GATEWAY_URL/invoice/booking/$BOOKING_ID" \
  -H "Authorization: Bearer $TOKEN")

echo "Invoice Response: $INVOICE_RESPONSE"
echo ""

echo "=== E2E Test Complete ==="