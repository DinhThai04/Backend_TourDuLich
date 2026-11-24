package com.example.file_service.repository;

import com.example.file_service.entity.FileReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileReferenceRepository extends JpaRepository<FileReference, Long> {
    Optional<FileReference> findByFileIdAndOwnerTypeAndOwnerId(String fileId, String ownerType, String ownerId);
    
    List<FileReference> findByOwnerTypeAndOwnerId(String ownerType, String ownerId);
    
    List<FileReference> findByFileId(String fileId);
    
    @Modifying
    @Transactional
    @Query("DELETE FROM FileReference fr WHERE fr.ownerType = :ownerType AND fr.ownerId = :ownerId")
    int deleteByOwnerTypeAndOwnerId(@Param("ownerType") String ownerType, @Param("ownerId") String ownerId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FileReference fr WHERE fr.fileId = :fileId AND fr.ownerType = :ownerType AND fr.ownerId = :ownerId")
    int deleteByFileIdAndOwnerTypeAndOwnerId(@Param("fileId") String fileId,
            @Param("ownerType") String ownerType, @Param("ownerId") String ownerId);

    @Modifying
    @Transactional
    @Query("DELETE FROM FileReference fr WHERE fr.fileId = :fileId")
    int deleteByFileId(@Param("fileId") String fileId);

    @Query(value = """
            SELECT COUNT(*)
            FROM file_reference ref
            JOIN file_mgmt referenced_file ON referenced_file.id = ref.file_id
            JOIN file_mgmt target_file ON target_file.url = :url
            WHERE (target_file.path IS NOT NULL AND referenced_file.path = target_file.path)
               OR referenced_file.url = target_file.url
            """, nativeQuery = true)
    long countReferencesForAssetUrl(@Param("url") String url);

    boolean existsByFileIdAndOwnerTypeAndOwnerId(String fileId, String ownerType, String ownerId);
}
