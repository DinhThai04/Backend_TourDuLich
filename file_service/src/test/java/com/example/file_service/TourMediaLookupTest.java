package com.example.file_service;

import com.example.file_service.entity.FileMgmt;
import com.example.file_service.mapper.FileMgmtMapper;
import com.example.file_service.repository.FileMgmtRepository;
import com.example.file_service.repository.FileRepository;
import com.example.file_service.service.FileService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TourMediaLookupTest {
    FileMgmtRepository repository=mock(FileMgmtRepository.class);
    FileService service=new FileService(mock(FileRepository.class),repository,mock(FileMgmtMapper.class),mock(org.springframework.jdbc.core.JdbcTemplate.class),new TestTransactionTemplate(org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED));
    @Test void filteredLookupIsBoundedNormalizesIdsAndDoesNotReadAllAssets() {
        when(repository.findByTourIdInOrderByIdAsc(List.of("1","2"))).thenReturn(List.of(
                FileMgmt.builder().id("a").tourId("2").url("https://res.cloudinary.com/demo/image/upload/a.jpg").build()));
        var result=service.getTourMedia(List.of("2","01","2"));
        assertEquals(1,result.size());assertEquals("2",result.getFirst().getTourId());
        verify(repository).findByTourIdInOrderByIdAsc(List.of("1","2"));verify(repository,never()).findAll();
    }
    @Test void missingFilterIsRejectedInsteadOfDumpingAllMedia() {
        assertThrows(ResponseStatusException.class,()->service.getTourMedia(null));
        verify(repository,never()).findAll();
    }
    @Test void invalidEmptyAndOversizedFiltersDoNotQuery() {
        for(var ids:List.of(List.<String>of(),List.of("null"),List.of("-1"),List.of("1 OR 1=1"),List.of("2147483648"),Collections.nCopies(51,"1")))
            assertThrows(ResponseStatusException.class,()->service.getTourMedia(ids));
        verifyNoInteractions(repository);
    }
}
