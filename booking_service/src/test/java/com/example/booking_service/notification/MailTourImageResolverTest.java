package com.example.booking_service.notification;

import com.example.booking_service.reservation.LifecycleTestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MailTourImageResolverTest {
    LifecycleTestDatabase db;
    MailTourImageResolver images;
    static final String IMAGE="https://res.cloudinary.com/xxcku2am/image/upload/v1791434013/tour12_01.jpg";
    @BeforeEach void setup() throws Exception {
        db=new LifecycleTestDatabase();images=new MailTourImageResolver(db.jdbc);
        db.jdbc.execute("DROP TABLE IF EXISTS file_mgmt");
        db.jdbc.execute("CREATE TABLE file_mgmt (id VARCHAR(80) PRIMARY KEY,tour_id VARCHAR(40),url VARCHAR(2048))");
        if (System.getenv("LIFECYCLE_TEST_JDBC_URL")!=null)
            db.jdbc.execute("ALTER TABLE file_mgmt MODIFY tour_id VARCHAR(40) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci");
    }
    @Test void choosesFirstSafeImageOnlyFromTheBookedTourAcrossDifferentCollations() {
        db.jdbc.update("INSERT INTO file_mgmt VALUES ('a','1','http://localhost/private'),('b','1',?),('c','2','https://res.cloudinary.com/other/image/upload/wrong.jpg')",IMAGE);
        assertEquals(IMAGE,images.findForDeparture(1));
    }
    @Test void missingDepartureOrPhotoIsOptional() {
        assertNull(images.findForDeparture(null));assertNull(images.findForDeparture(999));assertNull(images.findForDeparture(1));
    }
    @Test void databaseFailureDoesNotPreventRenderingAnEssentialReceipt() {
        db.jdbc.execute("DROP TABLE file_mgmt");assertNull(images.findForDeparture(1));
    }
    @Test void rejectsUnsafeOrNonImageUrls() {
        for(String value:new String[]{"http://res.cloudinary.com/a/image/upload/a.jpg","javascript:alert(1)",
                "https://res.cloudinary.com.evil.test/a/image/upload/a.jpg","https://user@res.cloudinary.com/a/image/upload/a.jpg",
                "https://res.cloudinary.com/a/raw/upload/a.pdf","https://res.cloudinary.com/a/image/upload/a.jpg\r\nInjected:true",
                "https://res.cloudinary.com/a/image/upload/a.jpg#fragment"}) assertFalse(MailTourImageResolver.isPublicImage(value),value);
        assertTrue(MailTourImageResolver.isPublicImage(IMAGE));
    }
}
