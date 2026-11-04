package com.example.tour_service.advice;

import org.springframework.stereotype.Component;
import java.text.Normalizer;
import java.util.*;
import static com.example.tour_service.advice.AdviceModels.*;

@Component
public class AdviceLocations {
    private static final Set<String> NORTH = Set.of("hanoi","sapa","ninhbinh","mocchau","bavi","halongyentu","hagiang","sonla");
    private static final Set<String> CENTRAL = Set.of("danang","quangnam","quangbinh");
    private static final Set<String> SOUTH = Set.of("hochiminh","angiang","tayninh","kiengiang","bariavungtau");
    public String key(String value) {
        if (value == null) return "";
        String normalized = Normalizer.normalize(value.replace('Đ','D').replace('đ','d'), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return Set.of("tphochiminh","hcm","saigon").contains(normalized) ? "hochiminh" : normalized;
    }
    public Region region(String name) {
        String k=key(name);
        return NORTH.contains(k) ? Region.NORTH : CENTRAL.contains(k) ? Region.CENTRAL : SOUTH.contains(k) ? Region.SOUTH : null;
    }
    public Set<Integer> aliases(AdviceCatalogRepository.Place place, List<AdviceCatalogRepository.Place> places) {
        Set<Integer> ids = new TreeSet<>();
        for (var candidate : places)
            if (Objects.equals(place.type(),candidate.type()) && key(place.name()).equals(key(candidate.name()))) ids.add(candidate.id());
        return ids;
    }
    public List<LocationOption> options(List<AdviceCatalogRepository.Place> places) {
        return places.stream().map(p -> {
            var ids=aliases(p,places); Region region=region(p.name());
            return new LocationOption(p.id(),p.name(),p.type(),region,ids.iterator().next(),List.copyOf(ids),region!=null);
        }).toList();
    }
}
