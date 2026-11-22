package com.example.file_service.repository;

import com.example.file_service.entity.FileReference;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface FileReferenceRepository extends JpaRepository<FileReference, Long> {
    Optional<FileReference> findByFileIdAndOwnerTypeAndOwnerId(String fileId, String ownerType, String ownerId);
    
    List<FileReference> findByOwnerTypeAndOwnerId(String ownerType, String ownerId);
    
    List<FileReference> findByFileId(String fileId);
    
    @Query("DELETE FROM FileReference fr WHERE fr.ownerType = :ownerType AND fr.ownerId = :ownerId")
    int deleteByOwnerTypeAndOwnerId(@Param("ownerType") String ownerType, @Param("ownerId") String ownerId);
    
    @Query("DELETE FROM FileReference fr WHERE fr.fileId = :fileId")
    int deleteByFileId(@Param("fileId") String fileId);
    
    boolean existsByFileIdAndOwnerTypeAndOwnerId(String fileId, String ownerType, String ownerId);
}