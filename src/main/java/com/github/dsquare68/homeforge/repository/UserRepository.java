package com.github.dsquare68.homeforge.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.github.dsquare68.homeforge.model.User;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
}
