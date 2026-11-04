package com.example.auth_service.entity;
import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
@Entity @Table(name="user_account_event") @Getter @Setter @NoArgsConstructor
public class UserAccountEvent {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(nullable=false) private Long targetUserId;
    @Column(nullable=false) private Long actorId;
    @Column(nullable=false,length=20) private String action;
    @Column(length=20) private String previousStatus;
    @Column(length=20) private String newStatus;
    @Column(nullable=false,length=1000) private String reason;
    @Column(nullable=false) private LocalDateTime createdAt;
}
