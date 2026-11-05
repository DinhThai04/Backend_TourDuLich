package com.example.search_service;
import com.example.search_service.document.TourDocument;
import com.example.search_service.repository.TourSearchRepository;
import com.example.search_service.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.data.elasticsearch.core.*;
import org.springframework.data.redis.core.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class CatalogSyncTest {
 TourSearchRepository repo=mock(TourSearchRepository.class);
 ElasticsearchOperations es=mock(ElasticsearchOperations.class);
 @SuppressWarnings("unchecked") RedisTemplate<String,Object> redis=mock(RedisTemplate.class);
 @SuppressWarnings("unchecked") ValueOperations<String,Object> values=mock(ValueOperations.class);
 TourSearchService service=new TourSearchService(repo,es,redis);
 TourDocument doc(int id,String title,long price){return new TourDocument(id,"P"+id,title,BigDecimal.valueOf(price),"Origin","Destination","Bus",List.of("2026-11-20"));}
 @BeforeEach void setup(){when(redis.opsForValue()).thenReturn(values);when(es.indexOps(TourDocument.class)).thenReturn(mock(IndexOperations.class));}
 @Test void snapshotReplacesAllFieldsAndDeletesOnlyToursMissingFromCompleteSource(){
  var old=doc(1,"Old",8000000);var orphan=doc(2,"Removed",4000000);var fresh=doc(1,"New title",3000000);
  fresh.setDestinationLocation("New destination");fresh.setVehicle("Plane");fresh.setDepartureDates(List.of("2026-12-01"));
  when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(old,orphan)));var result=service.replaceSnapshot(List.of(fresh));
  assertEquals(new TourSearchService.SyncResult(1,1,1),result);
  verify(repo).saveAll(List.of(fresh));verify(repo).deleteAllById(List.of(2));verify(values).increment("sgu-tour:search:v2:generation");
 }
 @Test void unchangedSnapshotDoesNotWriteOrInvalidate(){
  var doc=doc(1,"Same",3000000);when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(doc)));assertEquals(0,service.replaceSnapshot(List.of(doc)).updated());
  verify(repo,never()).saveAll(any());verify(values,never()).increment(anyString());
 }
 @Test void duplicateSnapshotIsRejectedBeforeAnyIndexMutation(){
  var doc=doc(1,"Tour",3000000);assertThrows(IllegalArgumentException.class,()->service.replaceSnapshot(List.of(doc,doc)));
  verifyNoInteractions(repo);
 }
 @Test void failedReplacementDoesNotDeleteOldIdsAndStillInvalidatesPotentialPartialWrites(){
  when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(doc(2,"Old",2000000))));when(repo.saveAll(any())).thenThrow(new IllegalStateException("fixture"));
  assertThrows(IllegalStateException.class,()->service.replaceSnapshot(List.of(doc(1,"New",3000000))));
  verify(repo,never()).deleteAllById(any());verify(values).increment("sgu-tour:search:v2:generation");
 }
 @Test void incompleteOrUnavailableHttpSourceLeavesIndexUntouched() throws Exception {
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/snapshot",e->{byte[] b="{\"source\":\"SGU_TOUR_CATALOG_V1\",\"complete\":false,\"total\":0,\"tours\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
  try {var reader=new CatalogSnapshotReader(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/snapshot","");
   var sync=new CatalogSynchronizer(reader,service,true);assertThrows(ResponseStatusException.class,sync::sync);verifyNoInteractions(repo,es,redis);
  }finally{server.stop(0);}
 }
 @Test void configuredCatalogKeyIsSentToTheSnapshotSource() throws Exception {
  var seen=new java.util.concurrent.atomic.AtomicReference<String>();
  var body="{\"source\":\"SGU_TOUR_CATALOG_V1\",\"complete\":true,\"total\":1,\"tours\":["
      +"{\"id\":1,\"tourProgram\":\"P1\",\"tourTitle\":\"Tour\",\"basePrice\":3000000,\"departureDates\":[\"2026-11-20\"]}]}";
  var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
  server.createContext("/snapshot",e->{seen.set(e.getRequestHeaders().getFirst("X-Catalog-Key"));byte[] b=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);e.sendResponseHeaders(200,b.length);e.getResponseBody().write(b);e.close();});server.start();
  try {var reader=new CatalogSnapshotReader(new ObjectMapper(),"http://127.0.0.1:"+server.getAddress().getPort()+"/snapshot","internal-key");
   assertEquals(1,reader.read().size());assertEquals("internal-key",seen.get());
  }finally{server.stop(0);}
 }
 @Test void snapshotWriteDoesNotRetainOldPriceOrTitle(){
  var fresh=doc(1,"New",3000000);when(repo.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));
  assertEquals(1,service.replaceSnapshot(List.of(fresh)).updated());
  verify(repo).saveAll(List.of(fresh));verify(repo,never()).findById(anyInt());
 }
 @Test void nullDepartureDateIsRejectedAsInvalidInput(){
  var doc=doc(1,"Tour",3000000);doc.setDepartureDates(java.util.Arrays.asList((String)null));
  assertThrows(IllegalArgumentException.class,()->service.replaceSnapshot(List.of(doc)));verifyNoInteractions(repo);
 }
}
