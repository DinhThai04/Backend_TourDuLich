package com.example.ai_service.conversation;

import java.util.*;

public final class TourReferences {
    private TourReferences() {}
    public static List<Integer> resolve(List<Integer> presented,List<Integer> positions) {
        if(positions==null || positions.isEmpty() || positions.size()>3 || new HashSet<>(positions).size()!=positions.size()) return List.of();
        if(positions.stream().anyMatch(p->p==null || p<1 || p>presented.size())) return List.of();
        return positions.stream().map(p->presented.get(p-1)).toList();
    }
}
