package com.example.file_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;

@Mapper(componentModel = "spring")
public interface FileMgmtMapper {

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "ownerId", ignore = true)
    @Mapping(target = "avatarOwnerId", ignore = true)
    @Mapping(target = "tourId", ignore = true)
    @Mapping(target = "locationId", ignore = true)
    FileMgmt toFileMgmt(FileInfo fileInfo);
}
