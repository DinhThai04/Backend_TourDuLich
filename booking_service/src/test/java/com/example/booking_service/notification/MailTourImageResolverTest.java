package com.example.booking_service.notification;

import com.example.booking_service.repository.httpclient.TourInternalClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MailTourImageResolverTest {
    TourInternalClient tourClient;
    MailTourImageResolver images;
    static final String IMAGE = "https://res.cloudinary.com/xxcku2am/image/upload/v1791434013/tour12_01.jpg";

    @BeforeEach
    void setup() {
        tourClient = mock(TourInternalClient.class);
        images = new MailTourImageResolver(tourClient);
    }

    @Test
    void choosesFirstSafeImageOnlyFromTheBookedTourAcrossDifferentCollations() {
        when(tourClient.getDeparture(1)).thenReturn(Map.of("id", 1, "image_url", IMAGE));
        assertEquals(IMAGE, images.findForDeparture(1));
    }

    @Test
    void missingDepartureOrPhotoIsOptional() {
        when(tourClient.getDeparture(999)).thenReturn(null);
        when(tourClient.getDeparture(1)).thenReturn(Map.of("id", 1));
        assertNull(images.findForDeparture(null));
        assertNull(images.findForDeparture(999));
        assertNull(images.findForDeparture(1));
    }

    @Test
    void databaseFailureDoesNotPreventRenderingAnEssentialReceipt() {
        when(tourClient.getDeparture(1)).thenThrow(new RuntimeException("tour service unavailable"));
        assertNull(images.findForDeparture(1));
    }

    @Test
    void rejectsUnsafeOrNonImageUrls() {
        for (String value : new String[]{"http://res.cloudinary.com/a/image/upload/a.jpg", "javascript:alert(1)",
                "https://res.cloudinary.com.evil.test/a/image/upload/a.jpg", "https://user@res.cloudinary.com/a/image/upload/a.jpg",
                "https://res.cloudinary.com/a/raw/upload/a.pdf", "https://res.cloudinary.com/a/image/upload/a.jpg\r\nInjected:true",
                "https://res.cloudinary.com/a/image/upload/a.jpg#fragment"}) {
            assertFalse(MailTourImageResolver.isPublicImage(value), value);
        }
        assertTrue(MailTourImageResolver.isPublicImage(IMAGE));
    }
}
