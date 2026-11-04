package com.example.ai_service.catalog;

import com.example.ai_service.conversation.ChatModels.*;
import java.util.List;

/** Only the public read APIs of phase B. No arbitrary path, SQL, auth header or mutation. */
public interface CatalogReader {
    List<Location> locations();
    SearchResult search(Criteria criteria,int page);
}
