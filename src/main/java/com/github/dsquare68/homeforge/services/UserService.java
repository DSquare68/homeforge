package com.github.dsquare68.homeforge.services;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.github.dsquare68.homeforge.model.User;
import com.github.dsquare68.homeforge.repository.UserRepository;

@Service
public class UserService {
	
	@Autowired
	UserRepository userRepository;
	@
	Autowired
	PasswordEncoder passwordEncoder;
	
	public boolean addUser(User user) {
		if(user==null)
			return false;
		user.setPassword(passwordEncoder.encode(user.getPassword()));
		User savedUser = userRepository.save(user);
		if(user.equals(savedUser))
			return true;
		else 
			return false;
	}

	public Optional<User> getUser(String username) {
		return userRepository.findByUsername(username);
	}

	public User isPasswordValid(String username, String password) {
		Optional<User> user = userRepository.findByUsername(username);
		if(user.isEmpty())
			return null;
		else if(passwordEncoder.matches(password,user.get().getPassword()))
			return user.get();
		else
			return null;
	}

}
