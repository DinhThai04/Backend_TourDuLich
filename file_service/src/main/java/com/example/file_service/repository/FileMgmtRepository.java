package com.example.file_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.file_service.entity.FileMgmt;

@Repository
public interface FileMgmtRepository extends JpaRepository<FileMgmt, String> {
    Optional<FileMgmt> findByAvatarOwnerId(String ownerId);
    List<FileMgmt> findByTourId(String tourId);
    List<FileMgmt> findByTourIdInOrderByIdAsc(List<String> tourIds);
    List<FileMgmt> findByTourIdAndUrlIn(String tourId, List<String> urls);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select f from FileMgmt f where f.path=:path or f.url=:url order by f.id")
    List<FileMgmt> lockAssetReferences(@org.springframework.data.repository.query.Param("path") String path,
            @org.springframework.data.repository.query.Param("url") String url);
    // Read-only compatibility check for legacy URL references outside file_mgmt in the project's shared database.
    @org.springframework.data.jpa.repository.Query(value="SELECT (SELECT COUNT(*) FROM location WHERE img=:url) + (SELECT COUNT(*) FROM users WHERE avatar=:url) + (SELECT COUNT(*) FROM tour_image_ids WHERE image_id=:url)", nativeQuery=true)
    long countDeliveryUrlReferences(@org.springframework.data.repository.query.Param("url") String url);
    org.springframework.data.domain.Page<FileMgmt> findByTourIdAndOwnerIdAndLocationIdIsNullAndAvatarOwnerIdIsNull(String tourId, String ownerId, org.springframework.data.domain.Pageable page);
    long countByTourIdAndOwnerIdAndLocationIdIsNullAndAvatarOwnerIdIsNull(String tourId, String ownerId);
    List<FileMgmt> findByLocationId(Integer locationId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select f from FileMgmt f where f.id=:id")
    Optional<FileMgmt> lockById(@org.springframework.data.repository.query.Param("id") String id);

}
