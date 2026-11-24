package com.example.auth_service.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String email;

    @Column
    private String avatar;

    @Builder.Default
    @Column(name = "avatar_version", nullable = false, columnDefinition = "BIGINT NOT NULL DEFAULT 0")
    private long avatarVersion = 0L;

    @Column
    private String phone;

    @Column
    private String address;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "role_id")
    private Role role;


    @Column
    private String googleId;   // id duy nhất từ Google

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Builder.Default
    private String provider = "LOCAL";

}

