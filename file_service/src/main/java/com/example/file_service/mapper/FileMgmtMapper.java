package com.example.file_service.mapper;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.example.file_service.dto.FileInfo;
import com.example.file_service.entity.FileMgmt;

@Mapper(componentModel = "spring")
public interface FileMgmtMapper {

//   FileMgmt  toUserResponse(FileMgmt user);
    @Mapping(target = "id", source = "name")
    FileMgmt toFileMgmt(FileInfo fileInfo);

    
}