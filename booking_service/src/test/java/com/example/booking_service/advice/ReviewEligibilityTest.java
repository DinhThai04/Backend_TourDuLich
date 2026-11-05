package com.example.booking_service.advice;
import com.example.booking_service.reservation.*;
import org.junit.jupiter.api.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ReviewEligibilityTest {
 LifecycleTestDatabase db; LifecycleTestDatabase.MutableClock clock; ReviewEligibilityService service;
 @BeforeEach void setup() throws Exception {
  db=new LifecycleTestDatabase(); clock=new LifecycleTestDatabase.MutableClock(LocalDateTime.of(2026,11,23,8,0));
  db.jdbc.execute("ALTER TABLE tour_departure ADD COLUMN return_date DATETIME");
  db.jdbc.update("UPDATE tour_departure SET return_date=?",LocalDateTime.of(2026,11,22,8,0));
  db.jdbc.update("INSERT INTO booking (id,account_id,tour_departure_id,status,created_at) VALUES (1,7,1,'CONFIRMED',?)",LocalDateTime.of(2026,11,1,8,0));
  db.jdbc.update("INSERT INTO customer(booking_id,booking_type) VALUES(1,'ADULT')");
  for(String table:List.of("payment","invoice")) db.jdbc.update("INSERT INTO "+table+"(booking_id,account_id,status,total_booking_tour_expense,day_of_pay) VALUES(1,7,'PAID',3350000,?)",LocalDateTime.of(2026,11,1,8,0));
  login(7);service=new ReviewEligibilityService(db.jdbc,new BookingAccess(),clock);
 }
 void login(int id){SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("test").header("alg","HS512").subject(""+id).build(),List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("SGU_SUPER_ADMIN"))));}
 @AfterEach void clear(){SecurityContextHolder.clearContext();}
 @Test void onlyThePaidTravelerAfterReturnCanReviewEvenWhenCallerIsAdmin(){
  assertTrue(service.check(1).eligible());login(8);assertFalse(service.check(1).eligible());login(7);
  clock.set(LocalDateTime.of(2026,11,21,8,0));assertFalse(service.check(1).eligible());
 }
 @Test void mismatchedOrRefundedOrUnpaidEvidenceCannotProduceVerifiedReview(){
  db.jdbc.update("UPDATE invoice SET total_booking_tour_expense=1");assertFalse(service.check(1).eligible());
  db.jdbc.update("UPDATE invoice SET total_booking_tour_expense=3350000");
  db.jdbc.update("UPDATE booking SET status='CANCELLED'");assertFalse(service.check(1).eligible());
  db.jdbc.update("UPDATE booking SET status='CONFIRMED'");db.jdbc.update("DELETE FROM payment");assertFalse(service.check(1).eligible());
 }
 @Test void suspendedDepartureAndUnknownTourAreNotEligible(){
  db.jdbc.update("UPDATE tour_departure SET operational_status='SUSPENDED'");assertFalse(service.check(1).eligible());assertFalse(service.check(999).eligible());
 }
}
