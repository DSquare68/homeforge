package com.github.dsquare68.homeforge.model;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "users")
@Data
public class User {

    public User(String f, String u, String e, String p, LocalDateTime now, LocalDateTime first, String r,String a) {
		this.fullName=f;
    	this.username=u;
		this.email=e;
		this.password=p;
		this.createdAt=now;
		this.lastLoginAt=first;
		this.role=r;
		this.avatarUrl=a;
	}
	public User() {
		
	}
	@Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String fullName;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private String role;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime lastLoginAt;
    
    private String avatarUrl;
}
