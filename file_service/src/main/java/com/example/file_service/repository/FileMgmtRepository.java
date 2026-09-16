package com.example.file_service.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.example.file_service.entity.FileMgmt;

@Repository
public interface FileMgmtRepository extends JpaRepository<FileMgmt, String> {
    Optional<FileMgmt> findByOwnerId(String ownerId);
    List<FileMgmt> findAll();
List<FileMgmt> findByTourId(String tourId);
    Optional<FileMgmt> findByUrl(String url);

}
