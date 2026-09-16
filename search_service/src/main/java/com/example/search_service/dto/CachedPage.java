package com.example.search_service.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CachedPage<T> {
    private List<T> content;
    private int page;
    private int size;
    private long total;
}
