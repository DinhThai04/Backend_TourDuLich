#!/bin/bash
docker exec sgu-tour-auth-service-1 unzip -p /app/app.jar "BOOT-INF/classes/com/example/auth_service/dto/request/AuthenticationRequest.class" > /tmp/AuthReq.class
javap -p /tmp/AuthReq.class
