#!/bin/bash
printf '%s' '{"email":"test@example.com","password":"password123"}' > /tmp/login.json
curl -s -X POST http://172.18.0.11:8080/auth/users/login -H "Content-Type: application/json" -d @/tmp/login.json
