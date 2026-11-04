package com.example.auth_service.repository;
import com.example.auth_service.entity.UserAccountEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
public interface UserAccountEventRepository extends JpaRepository<UserAccountEvent,Long> {
    @org.springframework.data.jpa.repository.Query("select coalesce(max(e.id),0) from UserAccountEvent e where e.targetUserId=:id")
    long sessionEpoch(@org.springframework.data.repository.query.Param("id") Long id);
    java.util.List<UserAccountEvent> findByTargetUserIdOrderByIdDesc(Long id,Pageable page);
}
